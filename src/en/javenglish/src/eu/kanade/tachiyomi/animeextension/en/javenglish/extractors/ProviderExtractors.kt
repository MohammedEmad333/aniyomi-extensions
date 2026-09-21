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
        val response = client.newCall(GET(url, requestHeaders)).execute()
        val finalUrl = response.request.url.toString()
        val document = response.use { it.asJsoup() }

        val script = document.selectFirst(
            "script:containsData(const sources), script:containsData(var sources), script:containsData(wc0)",
        )?.data() ?: return@runCatching emptyList()

        val playlistUrl = when {
            script.contains("sources") -> {
                val raw = script.substringAfter("hls': '", "").substringBefore("'")
                if (raw.isBlank()) return@runCatching emptyList()
                if (raw.startsWith("http")) {
                    raw
                } else {
                    String(Base64.decode(raw, Base64.DEFAULT))
                }
            }
            script.contains("wc0") -> {
                val encoded = Regex("""'([^']{20,})'""")
                    .find(script)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return@runCatching emptyList()

                val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
                Regex("""["']file["']\s*:\s*["']([^"']+)["']""")
                    .find(decoded)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return@runCatching emptyList()
            }
            else -> return@runCatching emptyList()
        }

        if (!playlistUrl.startsWith("http")) return@runCatching emptyList()

        val videoHeaders = requestHeaders.newBuilder()
            .set("Referer", finalUrl)
            .build()

        listOf(
            Video(
                videoUrl = playlistUrl,
                videoTitle = label,
                headers = videoHeaders,
                initialized = true,
            ),
        )
    }.getOrDefault(emptyList())
}

class TurboVidExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(
        url: String,
        label: String = "TurboVid",
        requestHeaders: Headers = Headers.headersOf(),
    ): List<Video> = runCatching {
        val response = client.newCall(GET(url, requestHeaders)).execute()
        val finalUrl = response.request.url.toString()
        val body = response.body.string()

        val mediaUrls = linkedSetOf<String>()

        MEDIA_REGEX.findAll(body).forEach { match ->
            mediaUrls += match.value.replace("\\/", "/")
        }

        decodeCandidates(body).forEach { decoded ->
            MEDIA_REGEX.findAll(decoded).forEach { match ->
                mediaUrls += match.value.replace("\\/", "/")
            }
        }

        val videoHeaders = requestHeaders.newBuilder()
            .set("Referer", finalUrl)
            .build()

        mediaUrls
            .filter { it.startsWith("http") }
            .filterNot { it.contains("/ads/", ignoreCase = true) || it.contains("preroll", ignoreCase = true) }
            .map { media ->
                Video(
                    videoUrl = media,
                    videoTitle = label,
                    headers = videoHeaders,
                    initialized = true,
                )
            }
    }.getOrDefault(emptyList())

    private companion object {
        val MEDIA_REGEX = Regex(
            """https?:\\?/\\?/[^"'<>\s]+?(?:\.m3u8|\.mp4|\.webm)(?:\?[^"'<>\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
    }
}

private fun decodeCandidates(body: String): Sequence<String> =
    Regex("""[A-Za-z0-9+/]{40,}={0,2}""")
        .findAll(body)
        .mapNotNull { match ->
            runCatching { String(Base64.decode(match.value, Base64.DEFAULT)) }.getOrNull()
        }
