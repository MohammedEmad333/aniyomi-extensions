package eu.kanade.tachiyomi.animeextension.en.javenglish

import eu.kanade.tachiyomi.animeextension.en.javenglish.extractors.DoodExtractor
import eu.kanade.tachiyomi.animeextension.en.javenglish.extractors.StreamTapeExtractor
import eu.kanade.tachiyomi.animeextension.en.javenglish.extractors.TurboVidExtractor
import eu.kanade.tachiyomi.animeextension.en.javenglish.extractors.VoeExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class JavEnglish : AnimeHttpSource() {

    override val name = "JAVEnglish"
    override val baseUrl = "https://javenglish.cc"
    override val lang = "en"
    override val supportsLatest = true
    override val supportsRelatedAnimes = false

    override val client = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    override fun popularAnimeRequest(page: Int): Request =
        GET(filterPageUrl(page, "popular"), headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseListing(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(filterPageUrl(page, "latest"), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseListing(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return latestUpdatesRequest(page)
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val url = if (page <= 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseListing(response)

    private fun filterPageUrl(page: Int, filter: String): String =
        if (page <= 1) "$baseUrl/?filter=$filter" else "$baseUrl/page/$page/?filter=$filter"

    private fun parseListing(response: Response): AnimesPage {
        val document = response.asJsoup()
        val anime = document.select("div.videos-list > article, article")
            .mapNotNull(::animeFromElement)
            .distinctBy { it.url }

        val hasNextPage = document.select(
            "a.next, a.next.page-numbers, .nav-links a.next, a[rel=next]",
        ).isNotEmpty()

        return AnimesPage(anime, hasNextPage)
    }

    private fun animeFromElement(element: Element): SAnime? {
        val anchor = element.selectFirst("a[href*=/video/]") ?: return null
        val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
        if (href.isBlank()) return null

        val title = element.selectFirst("a > header > span")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: anchor.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val image = element.selectFirst("a > div.post-thumbnail img, img")
        val thumbnail = image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.takeIf { it.isNotBlank() }

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            this.title = title
            thumbnail_url = thumbnail
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore(" – ").trim()

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("article img, .post img, .entry-content img")?.let(::imageUrl)

        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".entry-content p, .post-content p, article p")?.text()?.trim()

        val tags = document.select("a[rel=tag], .tags a, a[href*=/tag/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()

        val cast = document.select("a[href*=/cast/], a[href*=/actor/], a[href*=/actress/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()

        return SAnime.create().apply {
            this.title = title
            thumbnail_url = poster
            this.description = description
            genre = tags.ifBlank { null }
            artist = cast.ifBlank { null }
            status = SAnime.COMPLETED
        }
    }

    private fun imageUrl(element: Element): String? =
        element.attr("data-src").takeIf { it.isNotBlank() }
            ?: element.attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: element.attr("src").takeIf { it.isNotBlank() }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Video"

        return listOf(
            SEpisode.create().apply {
                name = title
                episode_number = 1F
                setUrlWithoutDomain(document.location())
            },
        )
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val response = client.newCall(GET(baseUrl + episode.url, headers)).awaitSuccess()
        val hosters = response.use { parseHosters(it.asJsoup()) }
        if (hosters.isEmpty()) return emptyList()

        val requestHeaders = headers.newBuilder()
            .set("Referer", baseUrl)
            .build()

        val resolved = mutableListOf<Hoster>()
        var foundPlayable = false

        for (hoster in hosters) {
            if (!foundPlayable) {
                val videos = extractProviderVideos(hoster, requestHeaders)
                if (videos.isNotEmpty()) {
                    resolved += Hoster(
                        hosterUrl = hoster.hosterUrl,
                        hosterName = hoster.hosterName,
                        videoList = videos,
                        lazy = false,
                    )
                    foundPlayable = true
                    continue
                }
            }

            resolved += Hoster(
                hosterUrl = hoster.hosterUrl,
                hosterName = hoster.hosterName,
                lazy = true,
            )
        }

        return resolved
    }

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override fun hosterListParse(response: Response): List<Hoster> =
        parseHosters(response.asJsoup())

    private fun parseHosters(document: Document): List<Hoster> {
        val sourceAnchors = document.select(
            "#sourcetabs a[href], div#sourcetabs a[href], a[href]",
        )

        val discovered = sourceAnchors
            .mapNotNull { anchor ->
                val url = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
                val label = anchor.text().trim()
                val looksLikeStream = label.startsWith("Stream", ignoreCase = true) ||
                    label.contains("Watch", ignoreCase = true)

                if (url.isBlank() || url.isLikelyAdMedia()) {
                    return@mapNotNull null
                }

                if (!looksLikeStream && !url.isKnownVideoHost()) {
                    return@mapNotNull null
                }

                val name = label.ifBlank {
                    when {
                        "streamtape" in url.lowercase() -> "StreamTape"
                        "voe." in url.lowercase() -> "VOE"
                        "dood" in url.lowercase() || "playmogo" in url.lowercase() -> "DoodStream"
                        "turbovid" in url.lowercase() -> "TurboVid"
                        else -> url.substringAfter("://").substringBefore("/").ifBlank { "Stream" }
                    }
                }

                makeHoster(url, name)
            }
            .distinctBy { it.hosterUrl }
            .sortedBy { it.hosterUrl.providerPriority() }

        if (discovered.isNotEmpty()) return discovered

        // Some titles expose only an iframe or use a provider domain we have not seen before.
        // Keep any non-ad HTTP iframe as a last-resort hoster and let the generic parser inspect it.
        return document.select("iframe[src]")
            .mapNotNull { iframe ->
                val url = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                if (
                    url.isBlank() ||
                    url.isLikelyAdMedia() ||
                    (!url.startsWith("http://") && !url.startsWith("https://"))
                ) {
                    null
                } else {
                    makeHoster(url, url.substringAfter("://").substringBefore("/").ifBlank { "Stream" })
                }
            }
            .distinctBy { it.hosterUrl }
    }

    private fun makeHoster(url: String, label: String): Hoster {
        if (url.isDirectMedia()) {
            return Hoster(
                hosterUrl = url,
                hosterName = label,
                videoList = listOf(
                    Video(
                        videoUrl = url,
                        videoTitle = label,
                        headers = headers,
                        initialized = true,
                    ),
                ),
                lazy = false,
            )
        }

        return Hoster(
            hosterUrl = url,
            hosterName = label,
            lazy = true,
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        hoster.videoList?.let { return it }

        if (hoster.hosterUrl.isDirectMedia()) {
            return listOf(
                Video(
                    videoUrl = hoster.hosterUrl,
                    videoTitle = hoster.hosterName,
                    headers = headers,
                    initialized = true,
                ),
            )
        }

        val hostHeaders = headers.newBuilder()
            .set("Referer", baseUrl)
            .build()

        extractProviderVideos(hoster, hostHeaders).takeIf { it.isNotEmpty() }?.let { return it }

        val response = client.newCall(GET(hoster.hosterUrl, hostHeaders)).awaitSuccess()
        return response.use { parseEmbeddedVideos(it, hoster, hostHeaders) }
    }

    private fun extractProviderVideos(hoster: Hoster, requestHeaders: Headers): List<Video> {
        val url = hoster.hosterUrl
        val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")

        return when {
            "streamtape" in host -> StreamTapeExtractor(client)
                .videosFromUrl(url, hoster.hosterName.ifBlank { "StreamTape" })

            "dood" in host || "playmogo" in host -> DoodExtractor(client)
                .videosFromUrl(url, hoster.hosterName.ifBlank { "DoodStream" })

            "voe" in host -> VoeExtractor(client)
                .videosFromUrl(url, hoster.hosterName.ifBlank { "VOE" }, requestHeaders)

            "turbovid" in host -> TurboVidExtractor(client)
                .videosFromUrl(url, hoster.hosterName.ifBlank { "TurboVid" }, requestHeaders)

            else -> emptyList()
        }
    }

    private fun parseEmbeddedVideos(
        response: Response,
        hoster: Hoster,
        videoHeaders: Headers,
    ): List<Video> {
        val body = response.body.string()
        val document = Jsoup.parse(body, hoster.hosterUrl)

        val mediaUrls = buildList {
            document.select("video[src], video source[src], source[src]").forEach { node ->
                val src = node.attr("abs:src").ifBlank { node.attr("src") }
                if (src.isDirectMedia() && !src.isLikelyAdMedia()) add(src)
            }
            MEDIA_REGEX.findAll(body).forEach { match ->
                match.value.replace("\\/","/").takeIf { !it.isLikelyAdMedia() }?.let(::add)
            }
        }.filter { it.isNotBlank() }.distinct()

        return mediaUrls.mapIndexed { index, url ->
            val title = when {
                "1080" in url -> "1080p"
                "720" in url -> "720p"
                "480" in url -> "480p"
                "360" in url -> "360p"
                mediaUrls.size == 1 -> hoster.hosterName
                else -> "${hoster.hosterName} ${index + 1}"
            }

            Video(
                videoUrl = url,
                videoTitle = title,
                headers = videoHeaders,
                initialized = true,
            )
        }
    }

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> {
        val document = response.asJsoup()
        val referer = hoster.hosterUrl.ifBlank { document.location() }
        val videoHeaders = headers.newBuilder()
            .set("Referer", referer)
            .build()

        val mediaUrls = buildList {
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.isDirectMedia() && !src.isLikelyAdMedia()) add(src)
            }
            document.select("video[src], video source[src], source[src]").forEach { node ->
                val src = node.attr("abs:src")
                if (src.isDirectMedia() && !src.isLikelyAdMedia()) add(src)
            }
            MEDIA_REGEX.findAll(document.html()).forEach { match ->
                match.value.replace("\\/","/").takeIf { !it.isLikelyAdMedia() }?.let(::add)
            }
        }.distinct()

        return mediaUrls.map { url ->
            Video(
                videoUrl = url,
                videoTitle = "Default",
                headers = videoHeaders,
                initialized = true,
            )
        }
    }

    override fun List<Video>.sortVideos(): List<Video> = this

    private fun String.providerPriority(): Int {
        val value = lowercase()
        return when {
            "dood" in value || "playmogo." in value -> 0
            "streamtape." in value -> 1
            "voe." in value -> 2
            "emturbovid." in value || "turbovid." in value -> 3
            else -> 9
        }
    }

    private fun String.isKnownVideoHost(): Boolean {
        val value = lowercase()
        return "streamtape." in value ||
            "voe." in value ||
            "doodstream." in value ||
            "playmogo." in value ||
            "emturbovid." in value ||
            "turbovidhls." in value ||
            "turbovid." in value
    }

    private fun String.isLikelyAdMedia(): Boolean {
        val value = lowercase()
        return "javx.cc/player.mp4" in value ||
            "/ads/" in value ||
            "/ad/" in value ||
            "preroll" in value ||
            "pre-roll" in value ||
            "vast" in value
    }

    private fun String.isDirectMedia(): Boolean {
        val clean = substringBefore('?').lowercase()
        return clean.endsWith(".mp4") ||
            clean.endsWith(".m3u8") ||
            clean.endsWith(".webm") ||
            contains("/player.mp4", ignoreCase = true)
    }

    companion object {
        private val MEDIA_REGEX = Regex(
            """https?:\\?/\\?/[^"'<>\\s]+?(?:\\.m3u8|\\.mp4|\\.webm)(?:\\?[^"'<>\\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
    }
}
