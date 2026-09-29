package eu.kanade.tachiyomi.extension.en.uncensoredreaders

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class UncensoredReadersFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Hasbic(),
        ToonCraze(),
        Yomowa(),
        MangaHe(),
        MangaSinCensura(),
    )
}

private abstract class GenericUncensoredSource : HttpSource() {

    final override val lang = "en"
    final override val supportsLatest = true
    override val client = network.cloudflareClient

    protected abstract val seriesPrefixes: List<String>

    protected open fun popularUrl(page: Int): String = pageUrl(baseUrl, page)
    protected open fun latestUrl(page: Int): String = pageUrl(baseUrl, page)
    protected open fun searchUrl(page: Int, query: String): String {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return "$baseUrl/?s=$q&page=$page"
    }

    override fun popularMangaRequest(page: Int): Request = GET(popularUrl(page), headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseMangaListing(response.asJsoup())

    override fun latestUpdatesRequest(page: Int): Request = GET(latestUrl(page), headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseMangaListing(response.asJsoup())

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) return popularMangaRequest(page)
        return GET(searchUrl(page, query), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        parseMangaListing(response.asJsoup())

    private fun parseMangaListing(document: Document): MangasPage {
        val mangas = document.select("a[href]")
            .mapNotNull(::mangaFromAnchor)
            .distinctBy { it.url }

        val hasNext = document.select(
            "a[rel=next], a.next, a.next.page-numbers, .pagination a.next, .nav-links a.next",
        ).isNotEmpty()

        return MangasPage(mangas, hasNext)
    }

    private fun mangaFromAnchor(anchor: Element): SManga? {
        val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
        if (!isSeriesUrl(href)) return null

        val image = anchor.selectFirst("img")
            ?: anchor.parent()?.selectFirst("img")

        val title = anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: image?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        return SManga.create().apply {
            setUrlWithoutDomain(href)
            this.title = title
            thumbnail_url = image?.let(::imageUrl)
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        val title = document.selectFirst("h1, .post-title h1, .manga-title h1, .story-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore(" - ").substringBefore(" | ").trim()

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(
                ".summary_image img, .manga-thumb img, .poster img, .series-cover img, article img",
            )?.let(::imageUrl)

        val description = document.selectFirst(
            ".summary__content, .description-summary, .manga-excerpt, .series-description, " +
                ".description, meta[property=og:description]",
        )?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim()?.takeIf { it.isNotBlank() }

        val genres = document.select(
            "a[href*='/genre/'], a[href*='/genres/'], a[href*='genre='], .genres a",
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()

        val author = document.selectFirst(
            ".author-content a, a[href*='/author/'], .post-content_item:has(.summary-heading:matchesOwn(Author)) .summary-content",
        )?.text()?.trim()?.takeIf { it.isNotBlank() }

        val body = document.text().lowercase(Locale.ROOT)
        val status = when {
            "completed" in body || "complete" in body -> SManga.COMPLETED
            "ongoing" in body || "on going" in body -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        return SManga.create().apply {
            this.title = title
            thumbnail_url = poster
            this.description = description
            genre = genres.ifBlank { null }
            this.author = author
            this.status = status
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("a[href]")
            .mapNotNull { anchor ->
                val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
                if (!isChapterUrl(href, anchor.text())) return@mapNotNull null

                val label = anchor.text().trim()
                    .takeIf { it.isNotBlank() }
                    ?: href.substringAfterLast('/').replace('-', ' ')

                SChapter.create().apply {
                    setUrlWithoutDomain(href)
                    name = label
                    chapter_number = chapterNumber(label, href)
                }
            }
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<SChapter> { it.chapter_number }
                    .thenByDescending { it.name },
            )
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        val preferred = document.select(
            ".reading-content img, .chapter-content img, .page-break img, " +
                ".reader-area img, .chapter-reader img, main img, article img",
        )

        val elements = if (preferred.isNotEmpty()) preferred else document.select("img")

        return elements
            .mapNotNull(::imageUrl)
            .filter(::looksLikePageImage)
            .distinct()
            .mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private fun isSeriesUrl(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').trimEnd('/')
        if (!clean.startsWith(baseUrl)) return false

        return seriesPrefixes.any { prefix ->
            val marker = "/${prefix.trim('/')}/"
            val rest = clean.removePrefix(baseUrl)
            if (!rest.startsWith(marker)) return@any false
            rest.removePrefix(marker).trim('/').split('/').size == 1
        }
    }

    private fun isChapterUrl(url: String, text: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').trimEnd('/')
        if (!clean.startsWith(baseUrl)) return false

        if ("/chapter-" in clean || "/chapter/" in clean || "/chapters/" in clean) return true

        val lowerText = text.lowercase(Locale.ROOT)
        val textLooksChapter = lowerText.contains("chapter") ||
            Regex("""\bch\.?\s*\d""", RegexOption.IGNORE_CASE).containsMatchIn(text)

        return seriesPrefixes.any { prefix ->
            val marker = "/${prefix.trim('/')}/"
            val rest = clean.removePrefix(baseUrl)
            if (!rest.startsWith(marker)) return@any false
            val parts = rest.removePrefix(marker).trim('/').split('/')
            if (parts.size < 2) return@any false
            val tail = parts.last()
            textLooksChapter ||
                tail.matches(Regex("""\d+(?:[.-]\d+)?""")) ||
                tail.startsWith("chapter-", ignoreCase = true)
        }
    }

    private fun chapterNumber(label: String, href: String): Float {
        val match = Regex(
            """(?:chapter|ch\.?\s*)?\s*(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE,
        ).find(label)
            ?: Regex("""(\d+(?:\.\d+)?)""").find(href.substringAfterLast('/'))

        return match?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: -1F
    }

    private fun imageUrl(element: Element): String? =
        element.attr("data-src").takeIf { it.isNotBlank() }
            ?: element.attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: element.attr("data-original").takeIf { it.isNotBlank() }
            ?: element.attr("data-url").takeIf { it.isNotBlank() }
            ?: element.attr("abs:src").takeIf { it.isNotBlank() }
            ?: element.attr("src").takeIf { it.isNotBlank() }

    private fun looksLikePageImage(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (listOf("logo", "avatar", "favicon", "banner", "adsystem", "emoji").any(lower::contains)) {
            return false
        }

        return lower.contains("/chapter") ||
            lower.contains("/chapters/") ||
            lower.contains("/uploads/") ||
            lower.contains("/reader/") ||
            lower.contains("/pages/") ||
            lower.contains("cdn.") ||
            Regex("""\.(?:jpe?g|png|webp|avif)(?:\?|$)""").containsMatchIn(lower)
    }

    protected fun pageUrl(path: String, page: Int): String =
        if (page <= 1) path else "${path.trimEnd('/')}?page=$page"
}

private class Hasbic : GenericUncensoredSource() {
    override val name = "Hasbic"
    override val baseUrl = "https://hasbicmanhwa.com"
    override val seriesPrefixes = listOf("series")

    override fun popularUrl(page: Int) =
        "$baseUrl/popular" + if (page > 1) "?page=$page" else ""

    override fun latestUrl(page: Int) =
        "$baseUrl/latest" + if (page > 1) "?page=$page" else ""

    override fun searchUrl(page: Int, query: String): String {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return "$baseUrl/catalog?q=$q&page=$page"
    }
}

private class ToonCraze : GenericUncensoredSource() {
    override val name = "ToonCraze"
    override val baseUrl = "https://tooncraze.com"
    override val seriesPrefixes = listOf("webtoon", "webtoons", "manga", "manhwa")

    override fun popularUrl(page: Int) =
        "$baseUrl/webtoons/?page=$page&sort=popular"

    override fun latestUrl(page: Int) =
        "$baseUrl/webtoons/?page=$page&sort=latest"

    override fun searchUrl(page: Int, query: String): String {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return "$baseUrl/?s=$q&post_type=wp-manga&page=$page"
    }
}

private class Yomowa : GenericUncensoredSource() {
    override val name = "Yomowa"
    override val baseUrl = "https://yomowa.com"
    override val seriesPrefixes = listOf("manhwa", "series", "manga")

    override fun popularUrl(page: Int) =
        "$baseUrl/popular" + if (page > 1) "?page=$page" else ""

    override fun latestUrl(page: Int) =
        "$baseUrl/latest" + if (page > 1) "?page=$page" else ""

    override fun searchUrl(page: Int, query: String): String {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return "$baseUrl/browse?q=$q&page=$page"
    }
}

private class MangaHe : GenericUncensoredSource() {
    override val name = "MangaHe"
    override val baseUrl = "https://mangahe.com"
    override val seriesPrefixes = listOf("manga")

    override fun popularUrl(page: Int) =
        "$baseUrl/manga/?page=$page&order=popular"

    override fun latestUrl(page: Int) =
        "$baseUrl/manga/?page=$page&order=update"

    override fun searchUrl(page: Int, query: String): String {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return "$baseUrl/?s=$q&post_type=wp-manga&page=$page"
    }
}

private class MangaSinCensura : GenericUncensoredSource() {
    override val name = "Manga Sin Censura"
    override val baseUrl = "https://mangasincensura.com"
    override val seriesPrefixes = listOf("manga", "manhwa", "webtoon")

    override fun popularUrl(page: Int) =
        "$baseUrl/manga/?page=$page&order=popular"

    override fun latestUrl(page: Int) =
        "$baseUrl/manga/?page=$page&order=update"

    override fun searchUrl(page: Int, query: String): String {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return "$baseUrl/?s=$q&post_type=wp-manga&page=$page"
    }
}
