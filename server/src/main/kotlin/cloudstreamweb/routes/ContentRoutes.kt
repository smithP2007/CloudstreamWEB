package cloudstreamweb.routes

import cloudstreamweb.errors.ErrorBody
import cloudstreamweb.errors.ProviderException
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

// ---------- DTOs (the only shape the SPA ever sees) -------------------------

@Serializable
data class ItemDto(
    val name: String,
    val url: String,
    val provider: String,
    val sourcePlugin: String? = null,
    val poster: String? = null,
    val type: String? = null,
)

@Serializable
data class HomeRowDto(val name: String, val items: List<ItemDto>)

@Serializable
data class HomeDto(val provider: String, val rows: List<HomeRowDto>)

@Serializable
data class SearchDto(val provider: String, val page: Int, val items: List<ItemDto>)

@Serializable
data class EpisodeDto(
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val poster: String? = null,
    val description: String? = null,
    val data: String,
    val dubStatus: String? = null,
)

@Serializable
data class DetailDto(
    val provider: String,
    val url: String,
    val name: String,
    val type: String,
    val poster: String? = null,
    val year: Int? = null,
    val plot: String? = null,
    val tags: List<String>? = null,
    val durationMinutes: Int? = null,
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class StreamDto(
    val name: String,
    val url: String,
    val source: String,
    val quality: Int,
    val headers: Map<String, String> = emptyMap(),
    val linkType: String,
)

@Serializable
data class StreamsDto(
    val provider: String,
    val data: String,
    val links: List<StreamDto>,
    val subtitles: List<SubtitleDto> = emptyList(),
)

@Serializable
data class SubtitleDto(
    val lang: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

// ---------- helpers -----------------------------------------------------------

object ContentResolver {
    /** Resolves a provider by name or sourcePlugin; null selects one with a main page. */
    fun provider(selected: String?): MainAPI {
        val all = APIHolder.allProviders
        if (selected != null && selected.isNotBlank() && selected != "(any)") {
            return all.firstOrNull {
                it.name == selected || it.sourcePlugin == selected
            } ?: throw ProviderException("PROVIDER_NOT_FOUND", selected)
        }
        return all.firstOrNull { it.hasMainPage }
            ?: throw ProviderException("PROVIDER_NOT_FOUND", "(none with main page)")
    }

    /**
     * Upstream exposes per-provider instance selection in its settings; mirror
     * that on every content call via the mainUrl override. Applies only when
     * the caller passes an explicit value, so plugin defaults stay intact.
     */
    fun overrideMainUrl(provider: MainAPI, value: String?) {
        if (!value.isNullOrBlank() && provider.mainUrl != value) provider.mainUrl = value
    }
}

private suspend fun <T> safe(label: String, timeoutMs: Long = 60_000, block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        withTimeout(timeoutMs) {
            try {
                block()
            } catch (t: Throwable) {
                System.err.println("[provider-error] $label: $t")
                throw ProviderException("PROVIDER_ERROR", "$label: ${t.message ?: t.javaClass.simpleName}", t)
            }
        }
    }

private fun SearchResponse.toDto(): ItemDto = ItemDto(
    name = name,
    url = url,
    provider = apiName,
    sourcePlugin = sourcePlugin,
    poster = posterUrl,
    type = type?.name,
)

/** sourcePlugin: defined on MainAPI by the library (set during registerMainAPI) */
private val SearchResponse.sourcePlugin: String?
    get() = (this as? com.lagradost.cloudstream3.MainAPI)?.sourcePlugin

/** LoadResponse -> flat DTO (episodes for series; synthetic one for movies/livestreams) */
private fun LoadResponse.toDetailDto(providerName: String): DetailDto {
    val episodeList = when (this) {
        is TvSeriesLoadResponse -> episodes.mapIndexed { i, e ->
            EpisodeDto(
                name = e.name, season = e.season, episode = e.episode,
                poster = e.posterUrl, description = e.description, data = e.data,
                dubStatus = null,
            )
        }
        is AnimeLoadResponse -> episodes.entries.flatMap { (status, list) ->
            list.mapIndexed { i, e ->
                EpisodeDto(
                    name = e.name, season = e.season, episode = e.episode,
                    poster = e.posterUrl, description = e.description, data = e.data,
                    dubStatus = status.name,
                )
            }
        }
        is MovieLoadResponse -> listOf(EpisodeDto(name = name, data = dataUrl))
        is LiveStreamLoadResponse -> listOf(EpisodeDto(name = name, data = dataUrl))
        else -> emptyList()
    }

    return DetailDto(
        provider = providerName,
        url = url,
        name = name,
        type = type.name,
        poster = posterUrl,
        year = year,
        plot = plot,
        tags = tags,
        durationMinutes = duration,
        episodes = episodeList,
    )
}

// ---------- routes ------------------------------------------------------------

object ContentRoutes {
    fun install(routing: io.ktor.server.routing.Routing) = with(routing) {
        get("/api/home") {
            val selected = call.request.queryParameters["provider"]
            val provider = ContentResolver.provider(selected)
            ContentResolver.overrideMainUrl(provider, call.request.queryParameters["mainUrl"])
            val firstPage = provider.mainPage.firstOrNull()
                ?: throw ProviderException("PROVIDER_ERROR", "${provider.name} has no main page")
            val home = safe("loadMainPage for ${provider.name}") {
                provider.getMainPage(
                    page = 1,
                    request = com.lagradost.cloudstream3.MainPageRequest(
                        name = firstPage.name,
                        data = firstPage.data,
                        horizontalImages = firstPage.horizontalImages,
                    )
                )
            }
            val dto = HomeDto(
                provider = provider.name,
                rows = (home?.items ?: emptyList()).map { row ->
                    HomeRowDto(
                        name = row.name,
                        items = row.list.map { it.toDto() },
                    )
                },
            )
            call.respond(dto)
        }

        get("/api/search") {
            val query = call.request.queryParameters["q"] ?: ""
            val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val provider = ContentResolver.provider(call.request.queryParameters["provider"])
            ContentResolver.overrideMainUrl(provider, call.request.queryParameters["mainUrl"])
            if (query.isBlank()) {
                call.respond(SearchDto(provider.name, page, emptyList()))
                return@get
            }
            val results = safe("search on ${provider.name}") {
                try {
                    val paginated = provider.search(query, page)
                    paginated?.items ?: provider.search(query) ?: emptyList()
                } catch (t: NotImplementedError) {
                    // provider without search support (its home items still work)
                    emptyList()
                }
            }
            call.respond(SearchDto(provider.name, page, results.take(60).map { it.toDto() }))
        }

        get("/api/detail") {
            val provider = ContentResolver.provider(call.request.queryParameters["provider"])
            ContentResolver.overrideMainUrl(provider, call.request.queryParameters["mainUrl"])
            val url = call.request.queryParameters["url"]
                ?: throw ProviderException("REPO_INVALID", "missing url")
            val response = safe("load from ${provider.name}") { provider.load(url) }
                ?: throw ProviderException("ENTRY_NOT_FOUND", url)
            call.respond(response.toDetailDto(provider.name))
        }

        get("/api/streams") {
            val provider = ContentResolver.provider(call.request.queryParameters["provider"])
            ContentResolver.overrideMainUrl(provider, call.request.queryParameters["mainUrl"])
            val data = call.request.queryParameters["data"]
                ?: throw ProviderException("REPO_INVALID", "missing data")

            val links = safe("loadLinks from ${provider.name}", timeoutMs = 90_000) {
                kotlinx.coroutines.coroutineScope {
                    val channel = Channel<ExtractorLink>(Channel.UNLIMITED)
                    val subs = Channel<SubtitleFile>(Channel.UNLIMITED)
                    val worker = launch {
                        try {
                            provider.loadLinks(
                                data, false,
                                { sub -> subs.trySend(sub) },
                            ) { link -> channel.trySend(link) }
                        } finally {
                            channel.close()
                            subs.close()
                        }
                    }
                    val collected = buildList<ExtractorLink> {
                        for (link in channel) add(link)
                    }
                    val collectedSubs = buildList<SubtitleFile> {
                        for (sub in subs) add(sub)
                    }
                    worker.join()
                    collected to collectedSubs
                }
            }
            call.respond(
                StreamsDto(
                    provider = provider.name,
                    data = data,
                    links = links.first.map { link ->
                        StreamDto(
                            name = link.name,
                            url = link.url,
                            source = link.source,
                            quality = link.quality,
                            headers = link.headers,
                            linkType = link.type.name,
                        )
                    },
                    subtitles = links.second.map { sub ->
                        SubtitleDto(
                            lang = sub.lang,
                            url = sub.url,
                            headers = sub.headers ?: emptyMap(),
                        )
                    },
                )
            )
        }
    }
}
