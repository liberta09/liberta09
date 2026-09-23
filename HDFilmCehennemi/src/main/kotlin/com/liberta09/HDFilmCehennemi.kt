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
import java.net.URLDecoder

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

    private fun safeBase64Decode(rawInput: String): String? {
        try {
            Log.d("HDCH", "safeBase64Decode raw input = $rawInput")
            var cleaned = try {
                URLDecoder.decode(rawInput, "UTF-8")
            } catch (_: Exception) {
                rawInput
            }

            cleaned = cleaned.replace("\\n", "")
                .replace("\\r", "")
                .replace("\\\"", "")
                .replace("\\/", "/")
                .replace("\"", "")
                .replace("'", "")
                .replace("\n", "")
                .replace("\r", "")
                .trim()

            val base64Cleaned = cleaned.replace(Regex("""[^A-Za-z0-9+/=\-_]"""), "")
            if (base64Cleaned.isEmpty()) return null

            var padded = base64Cleaned
            while (padded.length % 4 != 0) {
                padded += "="
            }

            val decodedBytes = try {
                Base64.decode(padded, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            } catch (_: Exception) {
                try {
                    Base64.decode(padded, Base64.DEFAULT)
                } catch (_: Exception) {
                    Base64.decode(padded, Base64.URL_SAFE)
                }
            }

            val result = String(decodedBytes, Charsets.UTF_8)
            Log.d("HDCH", "safeBase64Decode decoded result = $result")
            return result
        } catch (e: Exception) {
            Log.e("HDCH", "decryptLocalUrl Error: bad base-64 (${e.message})", e)
            return null
        }
    }

    private fun decryptLocalUrl(unpackedScript: String): String? {
        try {
            val partsMatch = """dc_\s*\(\s*\[\s*((?:['"][^'"]+['"]\s*,?\s*)+)\]\s*\)""".toRegex().find(unpackedScript)
                ?: """dc\s*\(\s*\[\s*((?:['"][^'"]+['"]\s*,?\s*)+)\]\s*\)""".toRegex().find(unpackedScript)
                ?: """\(\[\s*((?:['"][^'"]+['"]\s*,?\s*)+)\]\)""".toRegex().find(unpackedScript)

            if (partsMatch != null) {
                val rawParts = partsMatch.groupValues[1]
                val parts = rawParts.split(",").map { 
                    it.trim().trim('\'', '"').replace("\\/", "/") 
                }
                val joined = parts.joinToString("")
                Log.d("HDCH", "decryptLocalUrl raw input from parts = $joined")

                val decoded = safeBase64Decode(joined)
                if (decoded != null && (decoded.contains("http") || decoded.contains(".m3u8"))) {
                    return decoded
                }
            }

            val atobMatch = """atob\s*\(\s*["']([^"']+)["']\s*\)""".toRegex().find(unpackedScript)
            if (atobMatch != null) {
                val rawAtob = atobMatch.groupValues[1]
                val decoded = safeBase64Decode(rawAtob)
                if (decoded != null && (decoded.contains("http") || decoded.contains(".m3u8"))) {
                    return decoded
                }
            }

            return null
        } catch (e: Exception) {
            Log.e("HDCH", "decryptLocalUrl Error: bad base-64 (${e.message})", e)
            return null
        }
    }

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        videoID: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("HDCH", "invokeLocalSource: Fetching embed URL: $url for videoID: $videoID")
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "Referer" to "${mainUrl}/"
        )
        val response = app.get(url, headers = headers, interceptor = interceptor)
        Log.d("HDCH", "invokeLocalSource: HTTP Status Code: ${response.code}")

        val doc = response.document
        val rawScripts = doc.select("script").map { it.data() }
        Log.d("HDCH", "invokeLocalSource: Total script count in embed doc: ${rawScripts.size}")

        val scripts = rawScripts.map { script ->
            if (script.contains("eval(function(p,a,c,k,e,")) {
                val unpacked = getAndUnpack(script)
                Log.d("HDCH", "HDCH: Unpacked JS sample: ${unpacked.take(500)}")
                unpacked
            } else {
                script
            }
        }

        var lastUrl: String? = null

        // 1. Direct Regex scanning on unpacked scripts for explicit M3U8 / TXT URLs
        for ((scriptIdx, script) in scripts.withIndex()) {
            val directM3u8 = Regex("""https?://[^\s"'<>]+\.(?:m3u8|txt)[^\s"'<>]*""").find(script)?.value
                ?: Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(script)?.groupValues?.get(1)
            
            if (directM3u8 != null) {
                lastUrl = directM3u8
                Log.d("HDCH", "HDCH: Direct M3U8 URL found in script #$scriptIdx: $lastUrl")
                break
            }
        }

        // 2. Variable-based JS Decoder Analysis (Streambox interpreter flow)
        if (lastUrl.isNullOrEmpty()) {
            for ((scriptIdx, script) in scripts.withIndex()) {
                val sourceVarMatch = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*([a-zA-Z0-9_$]+)""").find(script)
                    ?: Regex("""file\s*:\s*([a-zA-Z0-9_$]+)""").find(script)
                
                if (sourceVarMatch != null) {
                    val sourceVar = sourceVarMatch.groupValues[1]
                    Log.d("HDCH", "HDCH: Found source variable '$sourceVar' in script #$scriptIdx")

                    // Search for assignment: var sourceVar = decoderName("pipe_string".split("|"))
                    val assignmentMatch = Regex("""\b$sourceVar\s*=\s*([a-zA-Z0-9_$]+)\s*\(\s*["']([^"']+\|[^\s"']+)["']\s*\.split""").find(script)
                    if (assignmentMatch != null) {
                        val funcName = assignmentMatch.groupValues[1]
                        val pipeStr = assignmentMatch.groupValues[2]
                        Log.d("HDCH", "HDCH: Rapidrame parts found = TRUE for var $sourceVar (length ${pipeStr.length})")
                        Log.d("HDCH", "HDCH: Decoder function name = $funcName")

                        // Extract function body
                        val funcRegex = Regex("""function\s+$funcName\s*\([^)]*\)\s*\{""")
                        var functionSource: String? = null
                        
                        for (s in scripts) {
                            val startIdx = funcRegex.find(s)?.range?.first
                            if (startIdx != null) {
                                var braceCount = 0
                                var endIdx = -1
                                var inString = false
                                var stringChar = ' '
                                
                                for (i in startIdx until s.length) {
                                    val c = s[i]
                                    if (!inString) {
                                        if (c == '"' || c == '\'') {
                                            inString = true
                                            stringChar = c
                                        } else if (c == '{') {
                                            braceCount++
                                        } else if (c == '}') {
                                            braceCount--
                                            if (braceCount == 0) {
                                                endIdx = i
                                                break
                                            }
                                        }
                                    } else {
                                        if (c == stringChar && s[i - 1] != '\\') {
                                            inString = false
                                        }
                                    }
                                }
                                
                                if (endIdx != -1) {
                                    functionSource = s.substring(startIdx, endIdx + 1)
                                    break
                                }
                            }
                        }

                        if (functionSource != null) {
                            Log.d("HDCH", "HDCH: Decoder function found = TRUE")
                            Log.d("HDCH", "HDCH: Decoder execution started")
                            try {
                                val decoded = runRapidrameDecoder(functionSource, pipeStr.split("|"))
                                if (decoded != null && (decoded.startsWith("http") || decoded.contains(".m3u8"))) {
                                    Log.d("HDCH", "HDCH: Decoder result = $decoded")
                                    lastUrl = decoded
                                    break
                                } else {
                                    Log.d("HDCH", "HDCH: Decoder execution FAILED = returned non-http result: $decoded")
                                }
                            } catch (e: Exception) {
                                Log.d("HDCH", "HDCH: Decoder execution FAILED = ${e.message}")
                            }
                        }
                    } else {
                        // Check if sourceVar is assigned a plain or Base64 string directly
                        val varValueMatch = Regex("""\b$sourceVar\s*=\s*["']([^"']+)["']""").find(script)
                            ?: Regex("""\b$sourceVar\s*=\s*atob\s*\(\s*["']([^"']+)["']\s*\)""").find(script)
                        
                        if (varValueMatch != null) {
                            val extractedValue = varValueMatch.groupValues[1]
                            if (extractedValue.startsWith("http")) {
                                lastUrl = extractedValue
                                break
                            } else {
                                val decoded = safeBase64Decode(extractedValue)
                                if (decoded != null && decoded.startsWith("http")) {
                                    lastUrl = decoded
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }

        if (lastUrl.isNullOrEmpty()) {
            Log.d("HDCH", "invokeLocalSource FAILED: Could not extract video URL for $url")
            return
        }

        if (!lastUrl.contains(".m3u8") && !lastUrl.contains(".txt") && !lastUrl.contains("master")) {
            Log.d("HDCH", "Final URL ($lastUrl) does not end with .m3u8/master.txt. ExtractorLink skipped.")
            return
        }

        Log.d("HDCH", "HDCH: Final M3U8 URL = $lastUrl")

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

        val originHost = try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            mainUrl
        }

        val streamHeaders = mapOf(
            "Referer" to refererHost,
            "Origin" to originHost,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "Accept" to "*/*",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site"
        )

        var statusCode = -1
        var contentType = "unknown"
        var isValidM3u8 = false
        var m3u8Text = ""

        try {
            val streamResponse = app.get(lastUrl, headers = streamHeaders, interceptor = interceptor)
            statusCode = streamResponse.code
            contentType = streamResponse.headers["Content-Type"] ?: "unknown"
            m3u8Text = streamResponse.text
            isValidM3u8 = m3u8Text.contains("#EXTM3U")
        } catch (e: Exception) {
            Log.e("HDCH", "DIAGNOSTIC - Fetching M3U8 failed: ${e.message}")
        }

        // Parse M3U8 variants
        val variantList = mutableListOf<M3u8Variant>()
        if (isValidM3u8 && m3u8Text.isNotBlank()) {
            val lines = m3u8Text.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val res = Regex("""RESOLUTION=(\d+x\d+)""").find(line)?.groupValues?.get(1) ?: "unknown"
                    val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val codecs = Regex("""CODECS=["']([^"']+)["']""").find(line)?.groupValues?.get(1) ?: "unknown"

                    val variantUrl = if (i + 1 < lines.size) {
                        val nextLine = lines[i + 1].trim()
                        if (nextLine.startsWith("http")) {
                            nextLine
                        } else if (nextLine.isNotBlank() && !nextLine.startsWith("#")) {
                            val baseUrl = lastUrl.substringBeforeLast("/") + "/"
                            baseUrl + nextLine
                        } else ""
                    } else ""

                    val isH264 = !codecs.contains("hevc", ignoreCase = true) &&
                                 !codecs.contains("hvc1", ignoreCase = true) &&
                                 !codecs.contains("av01", ignoreCase = true) &&
                                 !codecs.contains("vp9", ignoreCase = true)

                    if (variantUrl.isNotBlank()) {
                        variantList.add(M3u8Variant(res, bw, codecs, variantUrl, isH264))
                    }
                }
                i++
            }
        }

        // Output variant logcat format as requested
        variantList.forEachIndexed { idx, v ->
            Log.d("HDCH", "VARIANT #${idx + 1}:\nresolution=${v.resolution}\nbandwidth=${v.bandwidth}\ncodecs=${v.codecs}")
        }

        // Select the most compatible H.264 variant for LDPlayer / ExoPlayer compatibility
        val selectedVariant = variantList.filter { it.isH264 }.maxByOrNull { it.bandwidth }
            ?: variantList.maxByOrNull { it.bandwidth }

        if (selectedVariant != null) {
            Log.d("HDCH", "SELECTED VARIANT:\nurl=${selectedVariant.url}\nresolution=${selectedVariant.resolution}\nbandwidth=${selectedVariant.bandwidth}\ncodecs=${selectedVariant.codecs}")
        } else {
            Log.d("HDCH", "SELECTED VARIANT:\nurl=$lastUrl\nresolution=unknown\nbandwidth=0\ncodecs=unknown")
        }

        Log.d("HDCH", "PLAYER HEADERS:\nUser-Agent=${streamHeaders["User-Agent"]}\nOrigin=${streamHeaders["Origin"]}\nReferer=${streamHeaders["Referer"]}")

        val targetPlayUrl = selectedVariant?.url ?: lastUrl

        // Diagnostic test for the first segment (.ts / .m4s / .jpg)
        var firstSegmentUrl = "unknown"
        var segmentStatusCode = -1
        var segmentContentType = "unknown"
        var segmentSize = 0L
        var segmentError = "none"

        try {
            val variantM3u8Text = if (selectedVariant != null) {
                app.get(targetPlayUrl, headers = streamHeaders, interceptor = interceptor).text
            } else {
                m3u8Text
            }

            val segmentLine = variantM3u8Text.lines().map { it.trim() }.find {
                it.isNotBlank() && !it.startsWith("#")
            }

            if (segmentLine != null) {
                firstSegmentUrl = if (segmentLine.startsWith("http")) {
                    segmentLine
                } else {
                    targetPlayUrl.substringBeforeLast("/") + "/" + segmentLine
                }

                val segResponse = app.get(firstSegmentUrl, headers = streamHeaders, interceptor = interceptor)
                segmentStatusCode = segResponse.code
                segmentContentType = segResponse.headers["Content-Type"] ?: "unknown"
                segmentSize = segResponse.body.bytes().size.toLong()
                if (segmentStatusCode !in 200..299) {
                    segmentError = "HTTP $segmentStatusCode"
                }
            } else {
                segmentError = "No segment line found in variant M3U8"
            }
        } catch (e: Exception) {
            segmentError = e.message ?: "Exception fetching segment"
            Log.e("HDCH", "Segment diagnostic error: ${e.message}")
        }

        Log.d("HDCH", "SELECTED VARIANT URL: $targetPlayUrl")
        Log.d("HDCH", "FIRST SEGMENT URL: $firstSegmentUrl")
        Log.d("HDCH", "SEGMENT HTTP STATUS: $segmentStatusCode")
        Log.d("HDCH", "SEGMENT CONTENT-TYPE: $segmentContentType")
        Log.d("HDCH", "SEGMENT SIZE: $segmentSize bytes")
        Log.d("HDCH", "SEGMENT REQUEST HEADERS:\nUser-Agent=${streamHeaders["User-Agent"]}\nOrigin=${streamHeaders["Origin"]}\nReferer=${streamHeaders["Referer"]}")
        Log.d("HDCH", "SEGMENT ERROR: $segmentError")

        if (isValidM3u8) {
            try {
                val streams = M3u8Helper().m3u8Generation(M3u8Helper.M3u8Stream(targetPlayUrl, headers = streamHeaders))
                if (streams.isNotEmpty()) {
                    streams.forEach { stream ->
                        callback.invoke(
                            newExtractorLink(
                                source = source,
                                name = source,
                                url = stream.streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = streamHeaders
                                this.quality = stream.quality ?: Qualities.Unknown.value
                            }
                        )
                    }
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = source,
                            name = source,
                            url = targetPlayUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.headers = streamHeaders
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } catch (_: Exception) {
                callback.invoke(
                    newExtractorLink(
                        source = source,
                        name = source,
                        url = targetPlayUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = streamHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } else {
            callback.invoke(
                newExtractorLink(
                    source = source,
                    name = source,
                    url = targetPlayUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = streamHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    private data class M3u8Variant(
        val resolution: String,
        val bandwidth: Long,
        val codecs: String,
        val url: String,
        val isH264: Boolean
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDCH", "loadLinks: Started for page data: $data")
        val document = app.get(data, interceptor = interceptor).document
        val altLinks = document.select("div.alternative-links")

        altLinks.map { element ->
            element to element.attr("data-lang").uppercase()
        }.forEach { (element, langCode) ->
            val buttons = element.select("button.alternative-link")

            buttons.map { button ->
                button.text().replace("(HDrip Xbet)", "").trim() + " $langCode" to button.attr("data-video")
            }.forEach { (source, videoID) ->
                if (videoID.isBlank()) return@forEach

                val apiGet = app.get(
                    "${mainUrl}/video/$videoID/", interceptor = interceptor,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "X-Requested-With" to "fetch"
                    ),
                    referer = data
                ).text

                val rawHtml = AppUtils.tryParseJson<HDFC>(apiGet)?.html ?: apiGet

                val iframe = Regex("""data-src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)?.replace("\\", "")
                    ?: Regex("""src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)?.replace("\\", "")
                    ?: Jsoup.parse(rawHtml).selectFirst("iframe")?.attr("data-src")
                    ?: Jsoup.parse(rawHtml).selectFirst("iframe")?.attr("src")
                    ?: ""

                if (iframe.isEmpty()) {
                    Log.d("HDCH", "loadLinks FAILED: Could not find iframe URL in apiGet for videoID: $videoID")
                    return@forEach
                }

                val fullIframeUrl = fixUrlNull(iframe) ?: return@forEach
                Log.d("HDCH", "HDCH: Rapidrame URL = $fullIframeUrl")

                var finalIframe = fullIframeUrl
                if (finalIframe.contains("rapidrame") && finalIframe.contains("?rapidrame_id=")) {
                    finalIframe = "${mainUrl}/rplayer/" + finalIframe.substringAfter("?rapidrame_id=")
                } else if (finalIframe.contains("mobi")) {
                    val iframeDoc = Jsoup.parse(rawHtml)
                    finalIframe = fixUrlNull(iframeDoc.selectFirst("iframe")?.attr("data-src")) ?: finalIframe
                }

                Log.d("HDCH", "HDCH: /rplayer/ URL = $finalIframe")

                invokeLocalSource(source, finalIframe, videoID, subtitleCallback, callback)
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
