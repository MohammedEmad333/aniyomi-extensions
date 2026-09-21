package eu.kanade.tachiyomi.animeextension.en.javsubbed

import eu.kanade.tachiyomi.animeextension.en.javsubbed.extractors.DoodExtractor
import eu.kanade.tachiyomi.animeextension.en.javsubbed.extractors.StreamTapeExtractor
import eu.kanade.tachiyomi.animeextension.en.javsubbed.extractors.TurboVidExtractor
import eu.kanade.tachiyomi.animeextension.en.javsubbed.extractors.VoeExtractor
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

class JavSubbed : AnimeHttpSource() {

    override val name = "JAVSubbed"
    override val baseUrl = "https://javsubbed.net"
    override val lang = "en"
    override val supportsLatest = true
    override val supportsRelatedAnimes = false

    override val client = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    override fun popularAnimeRequest(page: Int): Request =
        GET(if (page <= 1) baseUrl else "$baseUrl/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(if (page <= 1) baseUrl else "$baseUrl/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListing(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return latestUpdatesRequest(page)
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val url = if (page <= 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseListing(response)

    private fun parseListing(response: Response): AnimesPage {
        val document = response.asJsoup()
        val primary = document.select("article, .post, .video, .item, .videos-list > *")
            .mapNotNull(::animeFromElement)
            .distinctBy { it.url }

        val anime = if (primary.isNotEmpty()) {
            primary
        } else {
            document.select("a[href]").mapNotNull { anchor ->
                val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
                if (!isTitleUrl(href)) return@mapNotNull null
                val title = anchor.text().trim().takeIf { it.isNotBlank() }
                    ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = title
                    thumbnail_url = anchor.selectFirst("img")?.let(::imageUrl)
                }
            }.distinctBy { it.url }
        }

        val hasNextPage = document.select(
            "a.next, a.next.page-numbers, .nav-links a.next, a[rel=next]",
        ).isNotEmpty()

        return AnimesPage(anime, hasNextPage)
    }

    private fun animeFromElement(element: Element): SAnime? {
        val anchor = element.selectFirst("a[href]") ?: return null
        val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
        if (!isTitleUrl(href)) return null

        val title = element.selectFirst("h1, h2, h3, .title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: anchor.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val image = element.selectFirst("img") ?: anchor.selectFirst("img")

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            this.title = title
            thumbnail_url = image?.let(::imageUrl)
        }
    }

    private fun isTitleUrl(url: String): Boolean {
        if (!url.startsWith(baseUrl)) return false
        val path = url.removePrefix(baseUrl).substringBefore('?').trim('/')
        if (path.isBlank()) return false
        val excluded = listOf("category/", "tag/", "actress/", "author/", "wp-", "page/")
        return excluded.none { path.startsWith(it, ignoreCase = true) } &&
            !path.contains("/") &&
            !path.endsWith(".mp4", ignoreCase = true)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore(" – ").trim()

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("article img, .post img, .entry-content img, .single-post img")
                ?.let(::imageUrl)

        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".entry-content p, .post-content p, article p")
                ?.text()?.trim()

        val genres = document.select("a[rel=tag], a[href*=/tag/], a[href*=/category/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()

        val cast = document.select("a[href*=/actress/], a[href*=/actor/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()

        return SAnime.create().apply {
            this.title = title
            thumbnail_url = poster
            this.description = description
            genre = genres.ifBlank { null }
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

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val response = client.newCall(GET(baseUrl + episode.url, headers)).awaitSuccess()
        val hosters = response.use { parseHosters(it.asJsoup()) }
        if (hosters.isEmpty()) {
            return listOf(
                Hoster(
                    hosterUrl = baseUrl + episode.url,
                    hosterName = "Page fallback",
                    lazy = false,
                ),
            )
        }

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
            .sortedBy { it.providerPriority() }

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

        if (hoster.hosterUrl.startsWith(baseUrl)) {
            val pageHeaders = headers.newBuilder()
                .set("Referer", baseUrl)
                .build()
            val response = client.newCall(GET(hoster.hosterUrl, pageHeaders)).awaitSuccess()
            val document = response.use { it.asJsoup() }
            val candidates = parseHosters(document)

            for (candidate in candidates) {
                val videos = extractProviderVideos(candidate, pageHeaders)
                if (videos.isNotEmpty()) return videos
            }

            val synthetic = Hoster(
                hosterUrl = hoster.hosterUrl,
                hosterName = hoster.hosterName,
                lazy = false,
            )
            val retry = client.newCall(GET(hoster.hosterUrl, pageHeaders)).awaitSuccess()
            return retry.use { parseEmbeddedVideos(it, synthetic, pageHeaders) }
        }

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

        val name = hoster.hosterName.lowercase()

        return when {
            "streamds" in name || "dood" in host || "playmogo" in host -> DoodExtractor(client)
                .videosFromUrl(url, hoster.hosterName.ifBlank { "StreamDS" })

            "streamtape" in host -> StreamTapeExtractor(client)
                .videosFromUrl(url, hoster.hosterName.ifBlank { "StreamTape" })

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

        val urls = buildList {
            document.select("video[src], video source[src], source[src]").forEach { node ->
                val src = node.attr("abs:src").ifBlank { node.attr("src") }
                if (src.isDirectMedia() && !src.isLikelyAdMedia()) add(src)
            }

            MEDIA_REGEX.findAll(body).forEach { match ->
                match.value.replace("\\/","/").takeIf { !it.isLikelyAdMedia() }?.let(::add)
            }
        }.filter { it.isNotBlank() }.distinct()

        return urls.mapIndexed { index, url ->
            Video(
                videoUrl = url,
                videoTitle = if (urls.size == 1) hoster.hosterName else "${hoster.hosterName} ${index + 1}",
                headers = videoHeaders,
                initialized = true,
            )
        }
    }

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> {
        val body = response.body.string()
        val document = Jsoup.parse(body, hoster.hosterUrl)

        val urls = buildList {
            document.select("video[src], video source[src], source[src]").forEach { node ->
                val src = node.attr("abs:src").ifBlank { node.attr("src") }
                if (src.isDirectMedia() && !src.isLikelyAdMedia()) add(src)
            }
            MEDIA_REGEX.findAll(body).forEach { match ->
                match.value.replace("\\/","/").takeIf { !it.isLikelyAdMedia() }?.let(::add)
            }
        }.distinct()

        return urls.map { url ->
            Video(
                videoUrl = url,
                videoTitle = hoster.hosterName,
                headers = headers,
                initialized = true,
            )
        }
    }

    override fun List<Video>.sortVideos(): List<Video> = this

    private fun Hoster.providerPriority(): Int {
        val name = hosterName.lowercase()
        val value = hosterUrl.lowercase()
        return when {
            "streamds" in name -> 0
            "dood" in value || "playmogo." in value -> 1
            "streamtape." in value -> 2
            "voe." in value -> 3
            "emturbovid." in value || "turbovid." in value -> 4
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
