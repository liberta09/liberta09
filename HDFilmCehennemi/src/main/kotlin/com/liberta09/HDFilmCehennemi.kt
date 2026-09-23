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

    private suspend fun invokeLocalSource(source: String, url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("HDCH", "invokeLocalSource: Fetching embed URL: $url")
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
            if (script.contains("eval(function(p,a,c,k,e,")) getAndUnpack(script) else script
        }

        var lastUrl: String? = null

        // Try extracting via the AST interpreter port (Streambox logic)
        for (script in scripts) {
            val pipeMatches = Regex("""["']([^"']+\|[^\s"']+)["']\s*\.split\s*\(\s*["']\|["']\s*\)""").findAll(script)
            val partsMatch = pipeMatches.firstOrNull()
            
            if (partsMatch != null) {
                val pipeStr = partsMatch.groupValues[1]
                Log.d("HDCH", "HDCH: Rapidrame parts found = TRUE (length ${pipeStr.length})")
                
                val funcNameMatch = Regex("""([a-zA-Z0-9_$]+)\s*\(\s*["'][^"']+["']\s*\.split""").find(script)
                val funcName = funcNameMatch?.groupValues?.get(1)?.trim()
                
                if (funcName != null) {
                    var functionSource: String? = null
                    val funcRegex = Regex("""function\s+$funcName\s*\([^)]*\)\s*\{""")
                    
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
                        Log.d("HDCH", "HDCH: Decoder function name = $funcName")
                        Log.d("HDCH", "HDCH: Decoder execution started")
                        
                        try {
                            val decoded = runRapidrameDecoder(functionSource, pipeStr.split("|"))
                            if (decoded != null) {
                                Log.d("HDCH", "HDCH: Decoder result = $decoded")
                                lastUrl = decoded
                                break
                            } else {
                                Log.d("HDCH", "HDCH: Decoder execution FAILED = returned null")
                            }
                        } catch (e: Exception) {
                            Log.d("HDCH", "HDCH: Decoder execution FAILED = ${e.message}")
                        }
                    } else {
                        Log.d("HDCH", "HDCH: Decoder function found = FALSE (name: $funcName)")
                    }
                }
            }
        }

        // Fallback: Try direct M3U8 regex in unpacked script
        if (lastUrl.isNullOrEmpty()) {
            for (script in scripts) {
                if (script.contains("sources:")) {
                    val directM3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(script)?.groupValues?.get(1)
                    if (directM3u8 != null) {
                        lastUrl = directM3u8
                        break
                    }
                    
                    val varMatch = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*([a-zA-Z0-9_]+)\s*""").find(script)
                    if (varMatch != null) {
                        val varName = varMatch.groupValues[1]
                        val varValueMatch = Regex("""$varName\s*=\s*["']([^"']+)["']""").find(script)
                            ?: Regex("""$varName\s*=\s*atob\s*\(\s*["']([^"']+)["']\s*\)""").find(script)
                        
                        if (varValueMatch != null) {
                            val extractedValue = varValueMatch.groupValues[1]
                            if (!extractedValue.contains("http") && !extractedValue.contains(".m3u8")) {
                                try {
                                    lastUrl = String(Base64.decode(extractedValue, Base64.DEFAULT))
                                } catch (e: Exception) {
                                    // Ignored
                                }
                            } else {
                                lastUrl = extractedValue
                            }
                        }
                    }
                    if (!lastUrl.isNullOrEmpty()) break
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

        callback.invoke(
            newExtractorLink(
                source = source,
                name = source,
                url = lastUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = mapOf(
                    "Referer" to refererHost,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"
                )
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
                Log.d("HDCH", "HDCH: AJAX file value = $fullIframeUrl")

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
