package com.horis.cncverse

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty

class NetflixMirrorProvider : MainAPI() {
    companion object {
        var context: Context? = null
    }

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    override var lang = "hi"
    override var mainUrl = "https://net52.cc"
    private var newUrl = "https://net22.cc"
    override var name = "Netflix"
    override val hasMainPage = true
    private var cookie_value = ""

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
        "X-Requested-With" to "XMLHttpRequest"
    )

    private suspend fun bypass(url: String): String {
        val res = app.get(url, headers = headers)
        return res.cookies["t_hash_t"] ?: ""
    }

    private fun convertRuntimeToMinutes(runtime: String?): Int? {
        if (runtime == null) return null
        return runtime.replace("m", "").replace("Min", "").trim().toIntOrNull()
    }

    private fun resolveApiUrl(): String {
        return mainUrl
    }

    private fun buildNewTvHeaders(ott: String, extra: Map<String, String>): Map<String, String> {
        val baseHeaders = headers.toMutableMap()
        baseHeaders["ott"] = ott
        baseHeaders.putAll(extra)
        return baseHeaders
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (cookie_value.isEmpty()) cookie_value = bypass(newUrl)
        val cookies = mapOf("t_hash_t" to cookie_value, "ott" to "nf", "hd" to "on")
        
        val document = app.get(
            "$mainUrl/mobile/home?app=1", 
            cookies = cookies, 
            headers = headers, 
            referer = "$mainUrl/mobile/home?app=1"
        ).document

        val items = document.select(".tray-container, #top10").map { it.toHomePageList() }
        return newHomePageResponse(items, false)
    }

    private fun Element.toHomePageList(): HomePageList {
        val name = select("h2, span").text()
        val items = select("article, .top10-post").mapNotNull { it.toSearchResult() }
        return HomePageList(name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val id = selectFirst("a")?.attr("data-post") ?: attr("data-post")
        if (id.isNullOrEmpty()) return null
        return newAnimeSearchResponse("", Id(id).toJson()) {
            this.posterUrl = "https://imgcdn.kim/poster/v/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (cookie_value.isEmpty()) cookie_value = bypass(newUrl)
        val cookies = mapOf("t_hash_t" to cookie_value, "hd" to "on", "ott" to "nf")
        
        val url = "$mainUrl/mobile/search.php?s=$query&t=${APIHolder.unixTime}"
        val data = app.get(url, referer = "$mainUrl/home", cookies = cookies).parsed<SearchData>()
        
        return data.searchResult.map { 
            newAnimeSearchResponse(it.t ?: "", Id(it.id).toJson()) {
                posterUrl = "https://imgcdn.kim/poster/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (cookie_value.isEmpty()) cookie_value = bypass(newUrl)
        val id = parseJson<Id>(url).id
        val cookies = mapOf("t_hash_t" to cookie_value, "hd" to "on", "ott" to "nf")
        
        val data = app.get(
            "$mainUrl/mobile/post.php?id=$id&t=${APIHolder.unixTime}",
            headers = headers,
            referer = "$mainUrl/home",
            cookies = cookies
        ).parsed<PostData>()

        val episodes = arrayListOf<Episode>()
        val title = data.title ?: ""
        val castList = data.cast?.split(",")?.map { it.trim() } ?: emptyList()
        val cast = castList.map { ActorData(Actor(it)) }
        val genre = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val rating = data.match?.replace("IMDb ", "")
        val runTime = convertRuntimeToMinutes(data.runtime?.toString())

        if (data.episodes.isNullOrEmpty() || data.episodes.first() == null) {
            episodes.add(newEpisode(LoadData(title, id)) { name = data.title })
        } else {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    this.name = it.t
                    this.episode = it.ep?.replace("E", "")?.toIntOrNull()
                    this.season = it.s?.replace("S", "")?.toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/epimg/150/${it.id}.jpg"
                    this.runTime = it.time?.replace("m", "")?.toIntOrNull()
                }
            }
            if (data.nextPageShow == 1 && data.nextPageSeason != null) {
                episodes.addAll(getEpisodes(title, id, data.nextPageSeason, 2))
            }
            data.season?.dropLast(1)?.forEach { seasonItem ->
                seasonItem?.id?.let { seasonId ->
                    episodes.addAll(getEpisodes(title, id, seasonId, 1))
                }
            }
        }

        val type = if (data.episodes.isNullOrEmpty() || data.episodes.first() == null) TvType.Movie else TvType.TvSeries
        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = "https://imgcdn.kim/poster/v/$id.jpg"
            backgroundPosterUrl = "https://imgcdn.kim/poster/h/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
            plot = data.desc
            year = data.year?.toIntOrNull()
            tags = genre
            actors = cast
            this.score = Score.from10(rating)
            this.duration = runTime
            this.contentRating = data.ua
        }
    }

    private suspend fun getEpisodes(title: String, eid: String, sid: String, page: Int): List<Episode> {
        val episodes = arrayListOf<Episode>()
        val cookies = mapOf("t_hash_t" to cookie_value, "hd" to "on", "ott" to "nf")
        var pg = page
        while (true) {
            val data = app.get(
                "$mainUrl/mobile/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg",
                headers = headers,
                referer = "$mainUrl/home",
                cookies = cookies
            ).parsed<EpisodesData>()

            if (data.episodes.isNullOrEmpty()) break

            data.episodes.mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    name = it.t
                    episode = it.ep?.replace("E", "")?.toIntOrNull()
                    season = it.s?.replace("S", "")?.toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/epimg/150/${it.id}.jpg"
                    this.runTime = it.time?.replace("m", "")?.toIntOrNull()
                }
            }
            if (data.nextPageShow == 0) break
            pg++
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val apiBase = resolveApiUrl()
        val id = parseJson<LoadData>(data).id
        val response = app.get(
            "$apiBase/newtv/player.php?id=$id",
            headers = buildNewTvHeaders("nf", mapOf("Usertoken" to ""))
        ).parsed<NewTvPlayerResponse>()

        if (response.status != "ok" || response.video_link.isNullOrBlank()) return false

        callback.invoke(
            newExtractorLink(name, name, response.video_link!!, type = ExtractorLinkType.M3U8) {
                this.referer = response.referer ?: apiBase
            }
        )
        return true
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                if (request.url.toString().contains(".m3u8")) {
                    val newRequest = request.newBuilder()
                        .header("Cookie", "hd=on")
                        .build()
                    return chain.proceed(newRequest)
                }
                return chain.proceed(request)
            }
        }
    }

    data class Id(val id: String)
    data class LoadData(val title: String, val id: String)

    data class SearchData(
        @JsonProperty("searchResult") val searchResult: List<SearchResultItem> = emptyList()
    )
    data class SearchResultItem(
        val id: String,
        val t: String?
    )

    data class PostData(
        val id: String?,
        val title: String?,
        val desc: String?,
        val year: String?,
        val cast: String?,
        val genre: String?,
        val match: String?,
        val runtime: Any?,
        val ua: String?,
        @JsonProperty("episodes") val episodes: List<EpisodeItem?>? = emptyList(),
        @JsonProperty("nextPageShow") val nextPageShow: Int? = 0,
        @JsonProperty("nextPageSeason") val nextPageSeason: String? = null,
        @JsonProperty("season") val season: List<SeasonItem?>? = emptyList()
    )

    data class EpisodeItem(
        val id: String,
        val t: String?,
        val ep: String?,
        val s: String?,
        val time: String?
    )

    data class SeasonItem(
        val id: String?
    )

    data class EpisodesData(
        @JsonProperty("episodes") val episodes: List<EpisodeItem>? = emptyList(),
        @JsonProperty("nextPageShow") val nextPageShow: Int? = 0
    )

    data class NewTvPlayerResponse(
        val status: String?,
        val video_link: String?,
        val referer: String?
    )
}
