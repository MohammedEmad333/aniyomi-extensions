package eu.kanade.tachiyomi.animeextension.en.javsubbed

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
        return response.use { parseHosters(it.asJsoup()) }
    }

    override fun hosterListParse(response: Response): List<Hoster> =
        parseHosters(response.asJsoup())

    private fun parseHosters(document: Document): List<Hoster> {
        val streamHosters = document.select("a[href]")
            .mapNotNull { anchor ->
                val label = anchor.text().trim()
                if (!label.startsWith("Stream", ignoreCase = true)) return@mapNotNull null

                val url = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
                if (url.isBlank() || url.isLikelyAdMedia()) null else makeHoster(url, label)
            }
            .distinctBy { it.hosterUrl }

        // Prefer the site's explicit stream buttons. Generic page iframes may be
        // short pre-roll/advertising media rather than the requested title.
        if (streamHosters.isNotEmpty()) return streamHosters

        return document.select("iframe[src]")
            .mapNotNull { iframe ->
                val url = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                if (url.isBlank() || url.isLikelyAdMedia()) null else makeHoster(url, "Primary")
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
            "streamtape" in host -> extractStreamTape(url, hoster.hosterName)
            "dood" in host || "playmogo" in host -> extractDood(url, hoster.hosterName)
            "voe" in host -> extractVoe(url, hoster.hosterName, requestHeaders)
            "turbovid" in host || "emturbovid" in host -> extractGenericProvider(url, hoster.hosterName, requestHeaders)
            else -> emptyList()
        }
    }

    private fun extractStreamTape(url: String, label: String): List<Video> = runCatching {
        val response = client.newCall(GET(url, headers)).execute()
        val document = response.use { it.asJsoup() }

        val targetLine = "document.getElementById('robotlink')"
        val script = document.selectFirst("script:containsData($targetLine)")
            ?.data()
            ?: return@runCatching emptyList()

        val first = script.substringAfter("$targetLine.innerHTML = '", "")
        if (first.isBlank()) return@runCatching emptyList()

        val videoUrl = "https:" +
            first.substringBefore("'") +
            script.substringAfter("+ ('xcd", "").substringBefore("'")

        if (videoUrl.length <= 8) return@runCatching emptyList()

        listOf(
            Video(
                videoUrl = videoUrl,
                videoTitle = label.ifBlank { "StreamTape" },
                headers = headers,
                initialized = true,
            ),
        )
    }.getOrDefault(emptyList())

    private fun extractDood(url: String, label: String): List<Video> = runCatching {
        val response = client.newCall(GET(url, headers)).execute()
        val finalUrl = response.request.url.toString()
        val body = response.body.string()

        if (!body.contains("'/pass_md5/")) return@runCatching emptyList()

        val md5 = body.substringAfter("'/pass_md5/").substringBefore("',")
        val token = md5.substringAfterLast("/")
        val host = java.net.URI(finalUrl).host ?: return@runCatching emptyList()

        val videoStart = client.newCall(
            GET(
                "https://$host/pass_md5/$md5",
                Headers.headersOf("Referer", finalUrl),
            ),
        ).execute().use { it.body.string() }

        if (videoStart.isBlank()) return@runCatching emptyList()

        val allowed = (('A'..'Z') + ('a'..'z') + ('0'..'9'))
        val random = (1..10).map { allowed.random() }.joinToString("")
        val videoUrl = "$videoStart$random?token=$token&expiry=${System.currentTimeMillis()}"
        val videoHeaders = Headers.Builder()
            .set("User-Agent", "Aniyomi")
            .set("Referer", "https://$host/")
            .build()

        listOf(
            Video(
                videoUrl = videoUrl,
                videoTitle = label.ifBlank { "DoodStream" },
                headers = videoHeaders,
                initialized = true,
            ),
        )
    }.getOrDefault(emptyList())

    private fun extractVoe(url: String, label: String, requestHeaders: Headers): List<Video> = runCatching {
        val response = client.newCall(GET(url, requestHeaders)).execute()
        val finalUrl = response.request.url.toString()
        val body = response.body.string()

        val candidates = linkedSetOf<String>()
        MEDIA_REGEX.findAll(body).forEach { match ->
            candidates += match.value.replace("\\/","/")
        }

        BASE64_REGEX.findAll(body).forEach { match ->
            val raw = match.value.trim('\'', '"')
            val decoded = runCatching {
                String(java.util.Base64.getDecoder().decode(raw))
            }.getOrNull() ?: return@forEach

            MEDIA_REGEX.findAll(decoded).forEach { media ->
                candidates += media.value.replace("\\/","/")
            }
        }

        candidates
            .filter { it.isDirectMedia() && !it.isLikelyAdMedia() }
            .map { media ->
                Video(
                    videoUrl = media,
                    videoTitle = label.ifBlank { "VOE" },
                    headers = requestHeaders.newBuilder().set("Referer", finalUrl).build(),
                    initialized = true,
                )
            }
    }.getOrDefault(emptyList())

    private fun extractGenericProvider(url: String, label: String, requestHeaders: Headers): List<Video> = runCatching {
        val response = client.newCall(GET(url, requestHeaders)).execute()
        val finalUrl = response.request.url.toString()
        val body = response.body.string()

        MEDIA_REGEX.findAll(body)
            .map { it.value.replace("\\/","/") }
            .filter { it.isDirectMedia() && !it.isLikelyAdMedia() }
            .distinct()
            .map { media ->
                Video(
                    videoUrl = media,
                    videoTitle = label,
                    headers = requestHeaders.newBuilder().set("Referer", finalUrl).build(),
                    initialized = true,
                )
            }
            .toList()
    }.getOrDefault(emptyList())

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
        private val BASE64_REGEX = Regex("""[A-Za-z0-9+/]{40,}={0,2}""")
        private val MEDIA_REGEX = Regex(
            """https?:\\?/\\?/[^"'<>\\s]+?(?:\\.m3u8|\\.mp4|\\.webm)(?:\\?[^"'<>\\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
    }
}
