package eu.kanade.tachiyomi.animeextension.hi.animedubhindi

import aniyomi.lib.pixeldrainextractor.PixelDrainExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class AnimeDubHindi : AnimeHttpSource() {

    override val name = "AnimeDubHindi"

    override val baseUrl = "https://www.animedubhindi.top"

    override val lang = "hi"

    override val supportsLatest = true

    private val pixelDrainExtractor = PixelDrainExtractor()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl.pageUrl("category/series", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl.pageUrl("", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListing(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isBlank()) {
        popularAnimeRequest(page)
    } else {
        GET(baseUrl.searchUrl(query, page), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseListing(response)

    private fun parseListing(response: Response): AnimesPage {
        val document = response.asJsoup()
        val entries = document.select("article").mapNotNull { it.toAnime() }
        val hasNextPage = document.select("a.next.page-numbers, .nav-links a.next, link[rel=next]").any()
        return AnimesPage(entries, hasNextPage)
    }

    private fun Element.toAnime(): SAnime? {
        val anchor = selectFirst("h2 a[href], .entry-title a[href], a[href]") ?: return null
        return SAnime.create().apply {
            setUrlWithoutDomain(anchor.attr("abs:href"))
            title = anchor.text().cleanTitle()
            thumbnail_url = selectFirst("img[src], img[data-src]")?.imageUrl()
        }
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val info = document.infoMap()
        val audio = info["Audio Tracks"]?.let { "\nAudio: $it" }.orEmpty()

        return SAnime.create().apply {
            title = (info["Title"] ?: document.selectFirst("meta[property=og:title]")?.attr("content")).orEmpty().cleanTitle()
            thumbnail_url = document.selectFirst("div.entry-content img[src], meta[property=og:image]")?.imageUrl()
            description = document.selectFirst("div.entry-content p")?.ownText()?.trim().orEmpty() + audio
            genre = info["Genres"]?.replace("|", ",")
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val linksPages = document.select("div.wp-block-button a[href]")
            .map { it.attr("abs:href") }
            .filter { it.contains("links.animedubhindi", ignoreCase = true) }
            .distinct()

        val episodes = linksPages.flatMap { linksPage ->
            runCatching {
                parseLinksPageEpisodes(linksPage)
            }.getOrDefault(emptyList())
        }.distinctBy { it.url }

        return episodes.ifEmpty {
            linksPages.mapIndexed { index, linksPage ->
                SEpisode.create().apply {
                    url = linksPage
                    name = if (linksPages.size == 1) "Movie" else "Movie ${index + 1}"
                    episode_number = (index + 1).toFloat()
                }
            }
        }.reversed()
    }

    private fun parseLinksPageEpisodes(linksPage: String): List<SEpisode> {
        val document = client.newCall(GET(linksPage, headers)).execute().asJsoup()
        val episodes = mutableListOf<SEpisode>()

        document.select("div.wp-block-group").forEachIndexed { index, block ->
            val episodeNumber = block.text().episodeNumber()
            if (episodeNumber != null && block.select("a[href]").hasDownloadLink()) {
                episodes += SEpisode.create().apply {
                    url = linksPage.withEpisodeQuery(episodeNumber)
                    name = "Episode $episodeNumber"
                    episode_number = episodeNumber.toFloat()
                }
            } else if (episodes.isEmpty() && block.select("a[href]").hasDownloadLink()) {
                val label = block.selectFirst("h2, h3, h4")?.ownText()?.ifBlank { null } ?: "Movie"
                episodes += SEpisode.create().apply {
                    url = linksPage.withSectionQuery(index)
                    name = label.cleanEpisodeName()
                    episode_number = (index + 1).toFloat()
                }
            }
        }

        document.select("div.pro-ep-card").forEachIndexed { index, card ->
            if (!card.select("a[href]").hasDownloadLink()) return@forEachIndexed
            val title = card.selectFirst(".pro-ep-title")?.text().orEmpty()
            val episodeNumber = title.episodeNumber() ?: (index + 1)
            episodes += SEpisode.create().apply {
                url = linksPage.withEpisodeQuery(episodeNumber)
                name = title.ifBlank { "Episode $episodeNumber" }.cleanEpisodeName()
                episode_number = episodeNumber.toFloat()
            }
        }

        return episodes
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val requestUrl = response.request.url
        val episode = requestUrl.queryParameter(EPISODE_QUERY)?.toIntOrNull()
        val section = requestUrl.queryParameter(SECTION_QUERY)?.toIntOrNull()

        val targets = when {
            episode != null -> document.select("div.wp-block-group, div.pro-ep-card")
                .filter { it.text().episodeNumber() == episode }
            section != null -> document.select("div.wp-block-group").getOrNull(section)?.let(::listOf).orEmpty()
            else -> document.select("div.wp-block-group, div.pro-ep-card").filter { it.select("a[href]").hasDownloadLink() }
        }.ifEmpty { listOf(document.body()) }

        return targets.flatMap { it.extractDownloadItems() }
            .distinctBy { it.url }
            .flatMap { item ->
                runCatching { extractVideos(item) }.getOrDefault(emptyList())
            }
            .distinctBy { it.videoUrl }
    }

    private fun Element.extractDownloadItems(): List<DownloadItem> {
        val items = mutableListOf<DownloadItem>()

        select("h4, .pro-quality-wrapper").forEach { section ->
            val quality = section.selectFirst(".pro-ep-quality")?.text()
                ?: section.ownText()
                ?: section.text()
            section.select("a[href]").forEach { anchor ->
                items += DownloadItem(anchor.text().ifBlank { "Link" }, anchor.attr("abs:href"), quality)
            }
        }

        if (items.isEmpty()) {
            select("a[href]").forEach { anchor ->
                items += DownloadItem(anchor.text().ifBlank { "Link" }, anchor.attr("abs:href"), text().qualityLabel())
            }
        }

        return items.filter { it.url.isPlayableHost() }
    }

    private fun extractVideos(item: DownloadItem): List<Video> {
        val url = resolveRedirect(item.url)
        val prefix = "${item.label} ${item.quality}".trim().replace(Regex("""\s+"""), " ")
        val lower = url.lowercase()

        return when {
            lower.contains("gdflix") -> gdFlixVideos(url, prefix)
            lower.contains("gofile") -> goFileVideos(url, prefix)
            lower.contains("pixeldrain") || lower.contains("pixeldra") -> pixelDrainExtractor.videosFromUrl(url, "$prefix - ")
            lower.contains("hubcloud") -> hubCloudVideos(url, prefix).ifEmpty { listOf(Video(url, "$prefix - HubCloud", url)) }
            lower.contains("gdtot") -> listOf(Video(url, "$prefix - GDTOT (external)", url))
            lower.contains("gdmirrorbot") -> listOf(Video(url, "$prefix - Multi (external)", url))
            lower.contains("terabox") || lower.contains("1024tera") -> listOf(Video(url, "$prefix - TeraBox (external)", url))
            lower.contains("mega.nz") -> listOf(Video(url, "$prefix - MEGA (external)", url))
            lower.contains("desidubanime") -> desiDubAnimeVideos(url, prefix)
            lower.isDirectVideoUrl() || lower.contains("r2.dev") || lower.contains("busycdn") || lower.contains("indexserver") -> {
                listOf(Video(url, "$prefix - Direct", url))
            }
            else -> emptyList()
        }
    }

    private fun hubCloudVideos(url: String, prefix: String): List<Video> {
        val uri = URI(url)
        val host = "${uri.scheme}://${uri.host}"
        val firstDocument = client.newCall(GET(url, headers)).execute().asJsoup()
        val rawHref = firstDocument.selectFirst("#download")?.attr("href").orEmpty()
        val href = rawHref.fixUrl(host).ifBlank { url }
        val document = client.newCall(GET(href, headers)).execute().asJsoup()
        val size = document.selectFirst("i#size")?.text().orEmpty()
        val quality = document.selectFirst("div.card-header")?.text()?.qualityLabel().orEmpty()

        return document.select("a.btn[href]").mapNotNull { anchor ->
            val label = anchor.ownText().lowercase()
            val link = anchor.attr("abs:href").ifBlank { anchor.attr("href").fixUrl(href) }
            val server = when {
                "download file" in label -> "HubCloud"
                "fsl server" in label -> "HubCloud FSL"
                "buzzserver" in label -> "HubCloud Buzz"
                "pixeldra" in label || "pixel" in label -> "HubCloud PixelDrain"
                "s3 server" in label -> "HubCloud S3"
                "fslv2" in label -> "HubCloud FSLv2"
                else -> return@mapNotNull null
            }
            val videoUrl = if ("pixeldra" in label || "pixel" in label) {
                if (link.contains("download")) link else "${link.baseUrl()}/api/file/${link.substringAfterLast("/")}?download"
            } else {
                link
            }
            Video(videoUrl, listOf(prefix, server, quality, size).filter { it.isNotBlank() }.joinToString(" - "), videoUrl)
        }
    }

    private fun gdFlixVideos(url: String, prefix: String): List<Video> {
        val document = client.newCall(GET(url, headers)).execute().asJsoup()
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ").ifBlank {
            document.selectFirst("title")?.text().orEmpty()
        }
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ")
        val quality = fileName.qualityLabel()
        val videos = mutableListOf<Video>()

        document.select("div.text-center a[href], a[href]").forEach { anchor ->
            val text = anchor.text()
            val link = anchor.attr("abs:href").ifBlank { anchor.attr("href").fixUrl(url) }

            when {
                text.contains("DIRECT DL", true) || text.contains("DIRECT SERVER", true) -> {
                    videos += Video(link, "$prefix - GDFlix Direct $quality [$fileSize]".trim(), link)
                }
                text.contains("CLOUD DOWNLOAD", true) -> {
                    val decoded = if ("url=" in link) {
                        URLDecoder.decode(link.substringAfter("url="), StandardCharsets.UTF_8.toString())
                    } else {
                        link
                    }
                    videos += Video(decoded, "$prefix - GDFlix Cloud $quality [$fileSize]".trim(), decoded)
                }
                text.contains("Instant DL", true) -> {
                    val finalUrl = client.newCall(GET(link, headers)).execute().use {
                        it.header("location")?.substringAfter("url=")?.ifBlank { null } ?: it.request.url.toString()
                    }
                    videos += Video(finalUrl, "$prefix - GDFlix Instant $quality [$fileSize]".trim(), finalUrl)
                }
                text.contains("GoFile", true) -> {
                    videos += goFileVideos(link, "$prefix - GDFlix")
                }
                text.contains("pixeldra", true) || text.contains("pixel", true) -> {
                    videos += pixelDrainExtractor.videosFromUrl(link, "$prefix - GDFlix ")
                }
            }
        }

        return videos
    }

    private fun desiDubAnimeVideos(url: String, prefix: String): List<Video> {
        val document = client.newCall(GET(url, headers)).execute().asJsoup()
        val watchUrl = document.selectFirst("a[href*=/watch/]")?.attr("abs:href") ?: url
        val watchDocument = client.newCall(GET(watchUrl, headers)).execute().asJsoup()
        val iframe = watchDocument.selectFirst("iframe[src]")?.attr("abs:src") ?: return listOf(Video(watchUrl, "$prefix - Watch online", watchUrl))
        return listOf(Video(iframe, "$prefix - Watch online", iframe))
    }

    private fun goFileVideos(url: String, prefix: String): List<Video> {
        val id = Regex("""/(?:\?c=|d/)([\da-zA-Z-]+)""").find(url)?.groupValues?.get(1) ?: return emptyList()
        val token = JSONObject(client.newCall(POST("$GOFILE_API/accounts", body = FormBody.Builder().build())).execute().body.string())
            .getJSONObject("data")
            .getString("token")
        val globalJs = client.newCall(GET("$GOFILE_URL/dist/js/global.js", headers)).execute().body.string()
        val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(globalJs)?.groupValues?.get(1) ?: return emptyList()
        val authHeaders = headers.newBuilder().set("Authorization", "Bearer $token").build()
        val json = JSONObject(client.newCall(GET("$GOFILE_API/contents/$id?wt=$wt", authHeaders)).execute().body.string())
        val children = json.getJSONObject("data").getJSONObject("children")

        return children.keys().asSequence().mapNotNull { key ->
            val file = children.getJSONObject(key)
            val link = file.optString("link").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val fileName = file.optString("name")
            val size = file.optLong("size").formatSize()
            val videoHeaders = Headers.headersOf("Cookie", "accountToken=$token")
            Video(link, "$prefix - Gofile ${fileName.qualityLabel()} [$size]".trim(), link, videoHeaders)
        }.toList()
    }

    private fun resolveRedirect(url: String): String {
        if (!url.contains("redirect.php", ignoreCase = true)) return url
        return client.newCall(GET(url, headers)).execute().use { it.request.url.toString() }
    }

    // ============================= Utilities ==============================

    private fun String.pageUrl(path: String, page: Int): String {
        val cleanPath = path.trim('/')
        return when {
            page <= 1 && cleanPath.isBlank() -> "$this/"
            page <= 1 -> "$this/$cleanPath/"
            cleanPath.isBlank() -> "$this/page/$page/"
            else -> "$this/$cleanPath/page/$page/"
        }
    }

    private fun String.searchUrl(query: String, page: Int): String {
        val path = if (page <= 1) "/" else "/page/$page/"
        return toHttpUrl().newBuilder()
            .encodedPath(path)
            .addQueryParameter("s", query)
            .build()
            .toString()
    }

    private fun String.withEpisodeQuery(episode: Int): String = toHttpUrl().newBuilder()
        .setQueryParameter(EPISODE_QUERY, episode.toString())
        .build()
        .toString()

    private fun String.withSectionQuery(section: Int): String = toHttpUrl().newBuilder()
        .setQueryParameter(SECTION_QUERY, section.toString())
        .build()
        .toString()

    private fun Document.infoMap(): Map<String, String> = select("ul.wp-block-list li").mapNotNull { li ->
        val text = li.text().replace(Regex("""\s+"""), " ").trim()
        val key = text.substringBefore(":").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        val value = text.substringAfter(":", "").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        key to value
    }.toMap()

    private fun Element.imageUrl(): String = attr("abs:src")
        .ifBlank { attr("abs:data-src") }
        .ifBlank { attr("abs:content") }
        .ifBlank { attr("src").takeIf { it.startsWith("//") }?.let { "https:$it" }.orEmpty() }

    private fun String.cleanTitle(): String = replace(Regex("""\s*(WEB-DL|BluRay|CR Dub)?\s*(Episodes?|Movie)?\s*Download.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.cleanEpisodeName(): String = replace(Regex("""\s+"""), " ").trim()

    private fun String.episodeNumber(): Int? = Regex("""Episode\s*:?\s*(\d+)""", RegexOption.IGNORE_CASE)
        .find(this)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

    private fun String.qualityLabel(): String = Regex("""(\d{3,4}P?[^|\]\n]*)""", RegexOption.IGNORE_CASE)
        .find(this)
        ?.value
        ?.trim()
        .orEmpty()

    private fun List<Element>.hasDownloadLink(): Boolean = any {
        val label = it.text().lowercase()
        val href = it.attr("abs:href").ifBlank { it.attr("href") }
        href.isPlayableHost() || label in DOWNLOAD_LINK_LABELS
    }

    private fun String.isPlayableHost(): Boolean {
        val lower = lowercase()
        return lower.contains("hubcloud") ||
            lower.contains("gdflix") ||
            lower.contains("gofile") ||
            lower.contains("pixeldrain") ||
            lower.contains("pixeldra") ||
            lower.contains("gdtot") ||
            lower.contains("gdmirrorbot") ||
            lower.contains("terabox") ||
            lower.contains("1024tera") ||
            lower.contains("mega.nz") ||
            lower.contains("desidubanime") ||
            lower.contains("r2.dev") ||
            lower.contains("busycdn") ||
            lower.contains("indexserver") ||
            lower.contains("redirect.php")
    }

    private fun String.isDirectVideoUrl(): Boolean = contains(Regex("""\.(mkv|mp4|m3u8)(\?|$)""", RegexOption.IGNORE_CASE))

    private fun String.fixUrl(base: String): String = when {
        isBlank() -> ""
        startsWith("//") -> "https:$this"
        startsWith("http") -> this
        startsWith("/") -> base.toHttpUrl().newBuilder().encodedPath(this).build().toString()
        else -> base.toHttpUrl().resolve(this).toString()
    }

    private fun String.baseUrl(): String = URI(this).let { "${it.scheme}://${it.host}" }

    private fun Long.formatSize(): String = when {
        this <= 0L -> ""
        this < 1024L * 1024 * 1024 -> "%.2f MB".format(this / 1024.0 / 1024)
        else -> "%.2f GB".format(this / 1024.0 / 1024 / 1024)
    }

    private data class DownloadItem(
        val label: String,
        val url: String,
        val quality: String,
    )

    companion object {
        private const val EPISODE_QUERY = "aniyomi_episode"
        private const val SECTION_QUERY = "aniyomi_section"
        private const val GOFILE_URL = "https://gofile.io"
        private const val GOFILE_API = "https://api.gofile.io"
        private val DOWNLOAD_LINK_LABELS = setOf("mega", "gdtot", "multi", "tera", "hcloud", "gdf", "fprs", "click here")
    }
}
