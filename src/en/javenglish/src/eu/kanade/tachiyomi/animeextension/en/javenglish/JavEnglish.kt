package eu.kanade.tachiyomi.animeextension.en.javenglish

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class JavEnglish : AnimeHttpSource() {

    override val name = "JAVEnglish"
    override val baseUrl = "https://javenglish.cc"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request =
        GET(pageUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseListing(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(pageUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseListing(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return popularAnimeRequest(page)
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val url = if (page <= 1) {
            "$baseUrl/?s=$encoded"
        } else {
            "$baseUrl/page/$page/?s=$encoded"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseListing(response)

    private fun pageUrl(page: Int): String =
        if (page <= 1) baseUrl else "$baseUrl/page/$page/"

    private fun parseListing(response: Response): AnimesPage {
        val document = response.asJsoup()

        val anime = document
            .select("a[href*=/video/]")
            .mapNotNull { anchor ->
                val href = anchor.attr("abs:href")
                if (href.isBlank()) return@mapNotNull null

                val title = (
                    anchor.attr("title").takeIf { it.isNotBlank() }
                        ?: anchor.text().takeIf { it.isNotBlank() }
                        ?: anchor.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                    )?.trim() ?: return@mapNotNull null

                val container = anchor.closest("article")
                    ?: anchor.closest(".item")
                    ?: anchor.parent()

                val image = (
                    anchor.selectFirst("img")
                        ?: container?.selectFirst("img")
                    )?.let { img ->
                    img.attr("data-src").takeIf { it.isNotBlank() }
                        ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
                        ?: img.attr("src").takeIf { it.isNotBlank() }
                }

                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = title
                    thumbnail_url = image
                }
            }
            .distinctBy { it.url }

        val hasNextPage = document.select(
            "a.next, a.next.page-numbers, .nav-links a.next, a[rel=next]",
        ).isNotEmpty()

        return AnimesPage(anime, hasNextPage)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore(" – ").trim()

        val poster = document.selectFirst(
            "meta[property=og:image], article img, .post img, .entry-content img",
        )?.let { element ->
            if (element.tagName() == "meta") {
                element.attr("content")
            } else {
                element.attr("data-src").takeIf { it.isNotBlank() }
                    ?: element.attr("data-lazy-src").takeIf { it.isNotBlank() }
                    ?: element.attr("src")
            }
        }

        val description = document.selectFirst(
            ".entry-content p, .post-content p, article p",
        )?.text()?.trim()

        val tags = document.select(
            "a[rel=tag], .tags a, a[href*=/tag/]",
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()

        val cast = document.select(
            "a[href*=/cast/], a[href*=/actor/], a[href*=/actress/]",
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()

        return SAnime.create().apply {
            this.title = title
            thumbnail_url = poster
            this.description = description
            genre = tags
            artist = cast.ifBlank { null }
            status = SAnime.COMPLETED
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: "Video"

        return listOf(
            SEpisode.create().apply {
                name = title
                episode_number = 1F
                setUrlWithoutDomain(document.location())
            },
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val referer = document.location()

        val mediaUrls = buildList {
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.isDirectMedia()) add(src)
            }

            document.select(
                "video[src], video source[src], source[src]",
            ).forEach { node ->
                val src = node.attr("abs:src")
                if (src.isDirectMedia()) add(src)
            }

            val html = document.html()
            MEDIA_REGEX.findAll(html).forEach { match ->
                add(match.value.replace("\\/","/"))
            }
        }.filter { it.isNotBlank() }
            .distinct()

        return mediaUrls.mapIndexed { index, url ->
            val quality = when {
                "1080" in url -> "1080p"
                "720" in url -> "720p"
                "480" in url -> "480p"
                "360" in url -> "360p"
                else -> if (mediaUrls.size == 1) "Default" else "Source ${index + 1}"
            }

            val videoHeaders = headers.newBuilder()
                .set("Referer", referer)
                .build()

            Video(url, quality, url, videoHeaders)
        }
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
