package eu.kanade.tachiyomi.animeextension.en.javenglish.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URI

class StreamTapeExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, label: String = "StreamTape"): List<Video> = runCatching {
        val baseUrl = "https://streamtape.com/e/"
        val normalized = if (!url.startsWith(baseUrl)) {
            val id = url.split("/").getOrNull(4) ?: return@runCatching emptyList()
            baseUrl + id
        } else {
            url
        }

        val document = client.newCall(GET(normalized)).execute().use { it.asJsoup() }
        val targetLine = "document.getElementById('robotlink')"
        val script = document.selectFirst("script:containsData($targetLine)")
            ?.data()
            ?: return@runCatching emptyList()

        val first = script.substringAfter("$targetLine.innerHTML = '", "")
        if (first.isBlank()) return@runCatching emptyList()

        val videoUrl = "https:" +
            first.substringBefore("'") +
            script.substringAfter("+ ('xcd", "").substringBefore("'")

        if (!videoUrl.startsWith("http")) return@runCatching emptyList()

        listOf(
            Video(
                videoUrl = videoUrl,
                videoTitle = label,
                initialized = true,
            ),
        )
    }.getOrDefault(emptyList())
}

class DoodExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, label: String = "DoodStream"): List<Video> = runCatching {
        val response = client.newCall(GET(url)).execute()
        val finalUrl = response.request.url.toString()
        val body = response.body.string()

        if (!body.contains("'/pass_md5/")) return@runCatching emptyList()

        val md5 = body.substringAfter("'/pass_md5/").substringBefore("',")
        val token = md5.substringAfterLast("/")
        val host = URI(finalUrl).host ?: return@runCatching emptyList()

        val start = client.newCall(
            GET(
                "https://$host/pass_md5/$md5",
                Headers.headersOf("Referer", finalUrl),
            ),
        ).execute().use { it.body.string() }

        if (start.isBlank()) return@runCatching emptyList()

        val allowed = (('A'..'Z') + ('a'..'z') + ('0'..'9'))
        val random = (1..10).map { allowed.random() }.joinToString("")
        val videoUrl = "$start$random?token=$token&expiry=${System.currentTimeMillis()}"

        val videoHeaders = Headers.Builder()
            .set("User-Agent", "Aniyomi")
            .set("Referer", "https://$host/")
            .build()

        listOf(
            Video(
                videoUrl = videoUrl,
                videoTitle = label,
                headers = videoHeaders,
                initialized = true,
            ),
        )
    }.getOrDefault(emptyList())
}

class VoeExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(
        url: String,
        label: String = "VOE",
        requestHeaders: Headers = Headers.headersOf(),
    ): List<Video> = runCatching {
        var response = client.newCall(GET(url, requestHeaders)).execute()
        var finalUrl = response.request.url.toString()
        var body = response.body.string()

        val redirect = Regex("""window\.location\.href\s*=\s*'([^']+)';""")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)

        if (!redirect.isNullOrBlank()) {
            response = client.newCall(GET(redirect, requestHeaders)).execute()
            finalUrl = response.request.url.toString()
            body = response.body.string()
        }

        val encoded = Regex("""<script[^>]+type=["']application/json["'][^>]*>\s*\["([^"]+)"\]\s*</script>""")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?: return@runCatching emptyList()

        val decrypted = decryptF7(encoded) ?: return@runCatching emptyList()
        val source = Regex("""["']source["']\s*:\s*["']([^"']+)["']""")
            .find(decrypted)
            ?.groupValues
            ?.getOrNull(1)
        val direct = Regex("""["']direct_access_url["']\s*:\s*["']([^"']+)["']""")
            .find(decrypted)
            ?.groupValues
            ?.getOrNull(1)

        val videoHeaders = requestHeaders.newBuilder()
            .set("Referer", finalUrl)
            .build()

        buildList {
            source?.takeIf { it.startsWith("http") }?.let { media ->
                add(Video(media, label, media, headers = videoHeaders))
            }
            direct?.takeIf { it.startsWith("http") }?.let { media ->
                add(Video(media, "$label MP4", media, headers = videoHeaders))
            }
        }
    }.getOrDefault(emptyList())

    private fun decryptF7(input: String): String? = runCatching {
        val rot13 = input.map { ch ->
            when (ch) {
                in 'A'..'Z' -> ((ch - 'A' + 13) % 26 + 'A'.code).toChar()
                in 'a'..'z' -> ((ch - 'a' + 13) % 26 + 'a'.code).toChar()
                else -> ch
            }
        }.joinToString("")

        val patterns = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
        val cleaned = patterns.fold(rot13) { acc, pattern -> acc.replace(pattern, "_") }
            .replace("_", "")

        val step1 = String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.ISO_8859_1)
        val shifted = step1.map { (it.code - 3).toChar() }.joinToString("")
        val reversed = shifted.reversed()
        String(Base64.decode(reversed, Base64.DEFAULT), Charsets.ISO_8859_1)
    }.getOrNull()
}

class TurboVidExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(
        url: String,
        label: String = "EmTurboVid",
        requestHeaders: Headers = Headers.headersOf(),
    ): List<Video> = runCatching {
        val response = client.newCall(GET(url, requestHeaders)).execute()
        val finalUrl = response.request.url.toString()
        val body = response.body.string()

        val urlPlay = Regex("""urlPlay\s*=\s*['"]([^'"]+)""")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?: return@runCatching emptyList()

        if (!urlPlay.startsWith("http")) return@runCatching emptyList()

        val origin = runCatching {
            val uri = URI(finalUrl)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(finalUrl)

        val videoHeaders = requestHeaders.newBuilder()
            .set("Referer", finalUrl)
            .set("Origin", origin)
            .build()

        listOf(Video(urlPlay, label, urlPlay, headers = videoHeaders))
    }.getOrDefault(emptyList())
}
