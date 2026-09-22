package com.liberta09

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 150L
    override var sequentialMainPageScrollDelay = 150L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())
            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/sayfano/home/" to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/sayfano/home-series/" to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/sayfano/categories/tavsiye-filmler-izle3/" to "Tavsiye Filmler",
        "${mainUrl}/load/page/sayfano/imdb7/" to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/sayfano/mostCommented/" to "En Çok Yorumlananlar",
        "${mainUrl}/load/page/sayfano/mostLiked/" to "En Çok Beğenilenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val url = request.data.replace("sayfano", page.toString())
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "Accept" to "*/*",
            "X-Requested-With" to "fetch"
        )
        val doc = app.get(url, headers = headers, referer = mainUrl, interceptor = interceptor)
        if (!doc.toString().contains("Sayfa Bulunamadı")) {
            val aa: HDFC = objectMapper.readValue(doc.toString())
            val document = Jsoup.parse(aa.html)
            val home = document.select("a").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, home)
        }
        return newHomePageResponse(request.name, emptyList())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title")
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()
        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)
            val title = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src")) ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))
            searchResults.add(
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl?.replace("/thumb/", "/list/") }
            )
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document
        val title = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags = document.select("div.post-info-genres a").map { it.text() }
        val year = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val actors = document.select("div.post-info-cast a").map {
            Actor(it.selectFirst("strong")!!.text(), it.select("img").attr("data-src"))
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recName = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?: fixUrlNull(it.selectFirst("img")?.attr("src"))
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) { this.posterUrl = recPosterUrl }
        }

        return if (tvType == TvType.TvSeries) {
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/", "")?.let { if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null }
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/", "")?.let { if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null }
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    data class DecOp(val name: String, val rotShift: Int = 0)

    private fun decryptLocalUrl(unpackedScript: String): String? {
        try {
            val partsMatch = """\(\[\s*((?:['"][^'"]+['"]\s*,?\s*)+)\]\)""".toRegex().find(unpackedScript)
            val parts = partsMatch?.groupValues?.get(1)?.split(",")?.map { 
                it.trim().trim('\'', '"').replace("\\/", "/") 
            } ?: return null

            val moduloMatch = """(\d+)\s*%\s*\(i\s*\+\s*(\d+)\)""".toRegex().find(unpackedScript)
            val magicNum = moduloMatch?.groupValues?.get(1)?.toLongOrNull() ?: 399756995L
            val magicOffset = moduloMatch?.groupValues?.get(2)?.toIntOrNull() ?: 5
            val funcBody = unpackedScript.substringAfter("function dc_").substringBefore("function d1x")
            val operations = mutableListOf<Pair<Int, DecOp>>()

            var index = funcBody.indexOf("atob(")
            while (index >= 0) {
                operations.add(Pair(index, DecOp("atob")))
                index = funcBody.indexOf("atob(", index + 1)
            }
            index = funcBody.indexOf("reverse")
            while (index >= 0) {
                operations.add(Pair(index, DecOp("reverse")))
                index = funcBody.indexOf("reverse", index + 1)
            }
            index = funcBody.indexOf("replace")
            while (index >= 0) {
                val block = funcBody.substring(index, minOf(index + 300, funcBody.length))
                var shift = 13
                val rotShiftMatch = """charCodeAt\(0\)\s*\+\s*(\d+)""".toRegex().find(block)
                if (rotShiftMatch != null) {
                    shift = rotShiftMatch.groupValues[1].toInt()
                } else {
                    val rotShiftMatch2 = """o\s*-\s*base\s*([+-])\s*(\d+)""".toRegex().find(block)
                    if (rotShiftMatch2 != null) {
                        val sign = rotShiftMatch2.groupValues[1]
                        val num = rotShiftMatch2.groupValues[2].toInt()
                        shift = if (sign == "-") (26 - num) % 26 else num
                    }
                }
                operations.add(Pair(index, DecOp("rot", shift)))
                index = funcBody.indexOf("replace", index + 1)
            }

            operations.sortBy { it.first }
            var result = parts.joinToString("")

            for (op in operations) {
                val action = op.second
                when (action.name) {
                    "reverse" -> result = result.reversed()
                    "atob" -> {
                        var paddedResult = result
                        while (paddedResult.length % 4 != 0) paddedResult += "="
                        result = String(Base64.decode(paddedResult, Base64.NO_WRAP), Charsets.ISO_8859_1)
                    }
                    "rot" -> {
                        val rotShift = action.rotShift
                        val rot = StringBuilder()
                        for (c in result) {
                            if (c in 'a'..'z') {
                                val shifted = c.code + rotShift
                                rot.append(if (shifted > 'z'.code) (shifted - 26).toChar() else shifted.toChar())
                            } else if (c in 'A'..'Z') {
                                val shifted = c.code + rotShift
                                rot.append(if (shifted > 'Z'.code) (shifted - 26).toChar() else shifted.toChar())
                            } else {
                                rot.append(c)
                            }
                        }
                        result = rot.toString()
                    }
                }
            }

            val unmix = StringBuilder()
            for (i in result.indices) {
                val charCode = result[i].code.toLong()
                val decryptedCode = (charCode - (magicNum % (i + magicOffset)) + 256) % 256
                unmix.append(decryptedCode.toInt().toChar())
            }
            return unmix.toString()
        } catch (e: Exception) {
            Log.e("HDCH", "decryptLocalUrl Error: ${e.message}")
            return null
        }
    }

    private fun decryptHhr7n(w1rhList: List<String>): String? {
        try {
            val w1rh = w1rhList.toMutableList()
            val p3kInitial = w1rh.size - 2
            val yl5d = p3kInitial % 7
            val o5c47 = 8 + (p3kInitial % 5)

            if (o5c47 >= w1rh.size || yl5d >= w1rh.size - 1) return null

            val ex7r1 = w1rh.removeAt(o5c47)
            val l12c = w1rh.removeAt(yl5d)
            var yl7o = w1rh.joinToString("")

            if (ex7r1.length > 2048) {
                yl7o = yl7o.reversed()
            }

            var j6285 = 0
            var pu9wu = 0
            for (j0vg in l12c.indices) {
                val qg5 = l12c[j0vg].code
                j6285 = (j6285 * 37 + qg5) % 241
                pu9wu = (pu9wu + ((qg5 shl 1) xor j0vg)) and 255
            }

            val e4ik = (j6285 * 3 + pu9wu) % 256
            val cnm5 = (pu9wu % 11) + 5
            var qd8 = ((pu9wu * 251 + j6285) % 65519) + 1

            for (j0vg in ex7r1.length - 1 downTo 0) {
                val w8q = ex7r1[j0vg]
                if (w8q == '7') {
                    var padded = yl7o
                    while (padded.length % 4 != 0) padded += "="
                    yl7o = String(Base64.decode(padded, Base64.NO_WRAP), Charsets.ISO_8859_1)
                } else if (w8q == '3') {
                    yl7o = yl7o.reversed()
                } else {
                    val uve0 = (26 - ((w8q.code - 96) % 26)) % 26
                    val sb = StringBuilder()
                    for (c in yl7o) {
                        if (c in 'a'..'z') {
                            val base = 'a'.code
                            val shifted = (c.code - base + uve0) % 26 + base
                            sb.append(shifted.toChar())
                        } else if (c in 'A'..'Z') {
                            val base = 'A'.code
                            val shifted = (c.code - base + uve0) % 26 + base
                            sb.append(shifted.toChar())
                        } else {
                            sb.append(c)
                        }
                    }
                    yl7o = sb.toString()
                }
            }

            if (l12c.length > 4096) {
                var padded = yl7o
                while (padded.length % 4 != 0) padded += "="
                yl7o = String(Base64.decode(padded, Base64.NO_WRAP), Charsets.ISO_8859_1)
            }

            val p3k = yl7o.length
            val t27s9 = IntArray(p3k)
            for (j0vg in p3k - 1 downTo 1) {
                qd8 = (qd8 * 97 + 41) % 65519
                t27s9[j0vg] = qd8 % (j0vg + 1)
            }

            val t9pi = yl7o.toCharArray()
            for (j0vg in 1 until p3k) {
                val w8fu = t27s9[j0vg]
                val kz8 = t9pi[j0vg]
                t9pi[j0vg] = t9pi[w8fu]
                t9pi[w8fu] = kz8
            }
            yl7o = String(t9pi)

            var z6l = e4ik
            val p1j3 = StringBuilder()
            for (j0vg in yl7o.indices) {
                val qg5 = yl7o[j0vg].code
                z6l = (z6l * 5 + cnm5) % 256
                val decryptedChar = qg5 xor z6l
                p1j3.append(decryptedChar.toChar())
                z6l = (z6l + qg5) % 256
            }

            return p1j3.toString()
        } catch (e: Exception) {
            Log.e("HDCH", "decryptHhr7n Error: ${e.message}")
            return null
        }
    }

    private suspend fun invokeLocalSource(source: String, url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("HDCH", "invokeLocalSource: Fetching embed URL: $url")
        val response = app.get(url, referer = "${mainUrl}/", interceptor = interceptor)
        Log.d("HDCH", "invokeLocalSource: HTTP Status Code: ${response.code}")

        val doc = response.document
        val scripts = doc.select("script").map { it.data() }
        Log.d("HDCH", "invokeLocalSource: Total script count in embed doc: ${scripts.size}")

        scripts.forEachIndexed { index, scriptData ->
            Log.d("HDCH", "Script #$index length: ${scriptData.length}")
            val keywords = listOf("m3u8", "hls", "jwplayer", "file:", "sources", "tz9", "hhr7n")
            if (keywords.any { scriptData.contains(it) }) {
                Log.d("HDCH", "Script #$index matched keywords! Content: ${scriptData.take(2000)}")
            }
        }

        var lastUrl: String? = null

        // 1. Try decryptHhr7n pattern (var tz9 = funcName("...".split("|")))
        for (script in scripts) {
            if (script.contains("tz9") || script.contains(".split(\"|\")") || script.contains(".split('|')")) {
                val match = Regex("""var\s+tz9\s*=\s*[a-zA-Z0-9_$]+\s*\(\s*["']([^"']+)["']\s*\.split\s*\(\s*["']\|["']\s*\)\s*\)""").find(script)
                if (match != null) {
                    val pipeStr = match.groupValues[1]
                    val decrypted = decryptHhr7n(pipeStr.split("|"))
                    if (!decrypted.isNullOrEmpty() && decrypted.startsWith("http")) {
                        lastUrl = decrypted
                        Log.d("HDCH", "invokeLocalSource: Decrypted via Hhr7n: $lastUrl")
                        break
                    }
                }
            }
        }

        // 2. Fallback: try old decryptLocalUrl or unpacked script
        if (lastUrl.isNullOrEmpty()) {
            val script = scripts.find { it.contains("sources:") }
            if (script != null) {
                val unpackedScript = getAndUnpack(script)
                val decryptedUrl = decryptLocalUrl(unpackedScript)
                lastUrl = decryptedUrl?.substringAfter("https")?.let { "https$it" }
                if (lastUrl.isNullOrEmpty()) {
                    lastUrl = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(script)?.groupValues?.get(1)
                        ?: Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpackedScript)?.groupValues?.get(1)
                }
            }
        }

        if (lastUrl.isNullOrEmpty()) {
            Log.d("HDCH", "invokeLocalSource: FAILED - M3U8 URL could not be extracted for url: $url")
            return
        }

        Log.d("HDCH", "invokeLocalSource: SUCCESS - Final extracted M3U8 URL: $lastUrl")

        val scriptWithSubtitles = scripts.find { it.contains("tracks:") || it.contains("captions") }
        if (scriptWithSubtitles != null) {
            val subData = scriptWithSubtitles.substringAfter("tracks: [").substringBefore("]")
            AppUtils.tryParseJson<List<SubSource>>("[${subData}]")?.filter { it.kind == "captions" }?.forEach {
                val subtitleUrl = fixUrlNull(it.file) ?: return@forEach
                subtitleCallback(newSubtitleFile(it.language.toString(), subtitleUrl))
            }
        }

        val refererHost = try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}/"
        } catch (_: Exception) {
            "${mainUrl}/"
        }

        Log.d("HDCH", "invokeLocalSource: Calling callback.invoke with M3U8 URL: $lastUrl")
        callback.invoke(
            newExtractorLink(
                source = source,
                name = source,
                url = lastUrl,
                type = ExtractorLinkType.M3U8
            ) {
                headers = mapOf("Referer" to refererHost)
                quality = Qualities.Unknown.value
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDCH", "loadLinks: Started for page data: $data")
        val document = app.get(data, interceptor = interceptor).document
        val altLinks = document.select("div.alternative-links")
        Log.d("HDCH", "loadLinks: Found ${altLinks.size} div.alternative-links blocks")

        altLinks.map { element ->
            element to element.attr("data-lang").uppercase()
        }.forEach { (element, langCode) ->
            val buttons = element.select("button.alternative-link")
            Log.d("HDCH", "loadLinks: Found ${buttons.size} button.alternative-link elements for lang $langCode")

            buttons.map { button ->
                button.text().replace("(HDrip Xbet)", "").trim() + " $langCode" to button.attr("data-video")
            }.forEach { (source, videoID) ->
                Log.d("HDCH", "loadLinks: Processing source '$source' with videoID: '$videoID'")
                if (videoID.isBlank()) return@forEach

                val apiGet = app.get(
                    "${mainUrl}/video/$videoID/", interceptor = interceptor,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "X-Requested-With" to "fetch"
                    ),
                    referer = data
                ).text

                Log.d("HDCH", "loadLinks: apiGet response (first 3000 chars): ${apiGet.take(3000)}")

                var iframe = Regex("""data-src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)?.replace("\\", "")
                    ?: Regex("""src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)?.replace("\\", "")
                    ?: ""

                if (iframe.isEmpty()) {
                    val iframeDoc = Jsoup.parse(apiGet)
                    iframe = fixUrlNull(iframeDoc.selectFirst("iframe")?.attr("data-src") ?: iframeDoc.selectFirst("iframe")?.attr("src")) ?: ""
                }

                if (iframe.isEmpty()) {
                    Log.d("HDCH", "loadLinks: FAILED - Could not find iframe URL in apiGet for videoID: $videoID")
                    return@forEach
                }

                val fullIframeUrl = fixUrlNull(iframe) ?: return@forEach
                Log.d("HDCH", "loadLinks: SUCCESS - Extracted embed iframe URL: $fullIframeUrl")

                invokeLocalSource(source, fullIframeUrl, subtitleCallback, callback)
            }
        }
        return true
    }

    private data class SubSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
    data class Results(@JsonProperty("results") val results: List<String> = arrayListOf())
    data class HDFC(@JsonProperty("html") val html: String, @JsonProperty("meta") val meta: Meta)
    data class Meta(@JsonProperty("title") val title: String, @JsonProperty("canonical") val canonical: String, @JsonProperty("keywords") val keywords: Boolean)
}
