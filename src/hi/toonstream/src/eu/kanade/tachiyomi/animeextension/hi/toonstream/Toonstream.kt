package eu.kanade.tachiyomi.animeextension.hi.toonstream

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URI
import kotlin.math.max

class Toonstream :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Toonstream"

    override val baseUrl = "https://toonstream.vip"

    override val lang = "hi"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val vidMolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client, preferences) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private fun currentBaseUrl(): String {
        val customUrl = preferences.getString(PREF_BASE_URL_KEY, "").orEmpty().trim().trimEnd('/')
        return customUrl.ifBlank { fetchRemoteBaseUrl() ?: baseUrl }
    }

    private fun fetchRemoteBaseUrl(): String? = runCatching {
        val body = client.newCall(GET(DOMAINS_URL, headers)).execute().body.string()
        json.decodeFromString<DomainsDto>(body).toonstream.trimEnd('/').takeIf(String::isNotBlank)
    }.getOrNull()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(currentBaseUrl().pageUrl("series", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(currentBaseUrl().pageUrl("category/anime", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListing(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val category = (filterList.find { it is CategoryFilter } as? CategoryFilter)?.toUriPart().orEmpty()
        val base = currentBaseUrl()

        return when {
            query.isNotBlank() -> GET("$base/page/$page/?s=$query", headers)
            category.isNotBlank() -> GET(base.pageUrl(category, page), headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseListing(response)

    private fun parseListing(response: Response): AnimesPage {
        val document = response.asJsoup()
        val entries = document.select("#movies-a > ul > li").map { it.toAnime() }
        val hasNextPage = document.select("a.next.page-numbers, .pagination a.next").any() ||
            document.select("#movies-a > ul > li").isNotEmpty()
        return AnimesPage(entries, hasNextPage)
    }

    private fun Element.toAnime(): SAnime = SAnime.create().apply {
        val anchor = selectFirst("article > a[href]") ?: selectFirst("a[href]")!!
        val poster = selectFirst("article > div.post-thumbnail > figure > img, article figure img, img")
            ?.imageUrl()

        setUrlWithoutDomain(anchor.attr("abs:href"))
        title = selectFirst("article > header > h2, header > h2, h2")
            ?.text()
            ?.cleanTitle()
            .orEmpty()
        thumbnail_url = poster
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("header.entry-header > h1")
                ?.text()
                ?.cleanTitle()
                .orEmpty()
            thumbnail_url = document.selectFirst("div.bghd > img, meta[property=og:image]")?.let {
                it.imageUrl().ifBlank { it.attr("abs:content") }
            }
            description = document.selectFirst("div.description > p")?.text()
            genre = document.select("a[rel=category tag], div.sgeneros a, div.genres a")
                .joinToString(", ") { it.text() }
                .ifBlank { null }
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val seasonButtons = document.select("div.aa-drp.choose-season > ul > li > a")

        if (seasonButtons.isEmpty()) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(pageUrl)
                    name = "Movie"
                    episode_number = 1F
                },
            )
        }

        val episodes = seasonButtons.flatMap { seasonButton ->
            val postId = seasonButton.attr("data-post")
            val seasonId = seasonButton.attr("data-season")
            val seasonName = seasonButton.text().ifBlank { "Season $seasonId" }
            val seasonNumber = seasonName.firstNumber()
                ?: seasonButton.attr("data-season").toIntOrNull()
                ?: 1
            val form = FormBody.Builder()
                .add("action", "action_select_season")
                .add("season", seasonId)
                .add("post", postId)
                .build()
            val ajaxHeaders = headers.newBuilder()
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Referer", pageUrl)
                .build()

            runCatching {
                client.newCall(POST("${currentBaseUrl()}/wp-admin/admin-ajax.php", ajaxHeaders, form))
                    .execute()
                    .asJsoup()
                    .select("article")
                    .mapIndexedNotNull { index, episodeElement ->
                        episodeElement.toEpisode(seasonNumber, index)
                    }
            }.getOrDefault(emptyList())
        }

        return episodes.ifEmpty {
            listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(pageUrl)
                    name = "Movie"
                    episode_number = 1F
                },
            )
        }.reversed()
    }

    private fun Element.toEpisode(season: Int, index: Int): SEpisode? {
        val anchor = selectFirst("article > a[href], a[href]") ?: return null
        val episodeTitle = selectFirst("article > header.entry-header > h2, header.entry-header > h2, h2")
            ?.text()
            ?.cleanTitle()
            .orEmpty()
        val episodeNumber = episodeTitle.firstNumber()?.toFloat() ?: (index + 1).toFloat()

        return SEpisode.create().apply {
            setUrlWithoutDomain(anchor.attr("abs:href"))
            name = episodeTitle.ifBlank { "Episode ${episodeNumber.toInt()}" }
            episode_number = episodeNumber
            scanlator = "Season $season"
        }
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframeUrls = document.select("#aa-options > div > iframe")
            .mapNotNull { iframe ->
                iframe.attr("data-src").ifBlank { iframe.attr("src") }.takeIf(String::isNotBlank)
            }
            .map { it.fixUrl(response.request.url.toString()) }
            .ifEmpty {
                document.select("iframe[src]").map { it.attr("abs:src") }
            }
            .distinct()

        return iframeUrls.parallelCatchingFlatMapBlocking { serverUrl ->
            val embedUrl = resolveNestedIframe(serverUrl)
            extractVideos(embedUrl)
        }
    }

    private fun resolveNestedIframe(url: String): String = runCatching {
        client.newCall(GET(url, headers)).execute().asJsoup()
            .selectFirst("iframe[src], iframe[data-src]")
            ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            ?.takeIf(String::isNotBlank)
            ?.fixUrl(url)
    }.getOrNull() ?: url

    private fun extractVideos(url: String): List<Video> {
        val lowerUrl = url.lowercase()
        return runCatching {
            when {
                lowerUrl.contains("vidmoly") -> runBlocking {
                    vidMolyExtractor.videosFromUrl(url, "VidMoly - ")
                }

                lowerUrl.contains("dood") || lowerUrl.contains("d000d") -> {
                    doodExtractor.videosFromUrl(url, "Dood")
                }

                lowerUrl.contains("filemoon") -> {
                    filemoonExtractor.videosFromUrl(url, "FileMoon - ")
                }

                lowerUrl.contains("streamwish") ||
                    lowerUrl.contains("cdnwish") ||
                    lowerUrl.contains("wish") ||
                    lowerUrl.contains("filelions") -> {
                    streamWishExtractor.videosFromUrl(url) { "StreamWish - $it" }
                }

                lowerUrl.contains("vidhide") ||
                    lowerUrl.contains("streamhide") ||
                    lowerUrl.contains("guccihide") ||
                    lowerUrl.contains("streamvid") ||
                    lowerUrl.contains("earnvid") ||
                    lowerUrl.contains("streamhg") -> runBlocking {
                    vidHideExtractor.videosFromUrl(url) { "VidHide - $it" }
                }

                lowerUrl.contains("streamruby") -> {
                    streamRubyVideos(url)
                }

                lowerUrl.contains("awstream") ||
                    lowerUrl.contains("zephyrflick") ||
                    lowerUrl.contains("play.zephyrflick") -> {
                    awsStreamVideos(url)
                }

                else -> universalExtractor.videosFromUrl(url, headers)
            }
        }.getOrDefault(emptyList())
    }

    private fun streamRubyVideos(url: String): List<Video> {
        val fixedUrl = if (url.contains("/e/")) url.replace("/e/", "/") else url
        val text = client.newCall(GET(fixedUrl, headers)).execute().body.string()
        val videoUrl = Regex("""file:\s*["']([^"']+?m3u8[^"']*)["']""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()

        return playlistUtils.extractFromHls(
            videoUrl,
            referer = fixedUrl,
            videoNameGen = { "StreamRuby - $it" },
        )
    }

    private fun awsStreamVideos(url: String): List<Video> {
        val host = url.base()
        val hash = url.substringAfterLast("/")
        val apiUrl = "$host/player/index.php?data=$hash&do=getVideo"
        val form = FormBody.Builder()
            .add("hash", hash)
            .add("r", host)
            .build()
        val ajaxHeaders = headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", url)
            .build()
        val body = client.newCall(POST(apiUrl, ajaxHeaders, form)).execute().body.string()
        val videoSource = json.decodeFromString<AwsStreamDto>(body).videoSource.takeIf(String::isNotBlank)
            ?: return emptyList()

        val subtitles = runCatching {
            val document = client.newCall(GET(url, headers)).execute().asJsoup()
            val script = document.selectFirst("script:containsData(kind):containsData(captions)")?.data().orEmpty()
            Regex(""""kind"\s*:\s*"captions"\s*,\s*"file"\s*:\s*"(https.*?\.srt)"""")
                .findAll(script)
                .map { Track(it.groupValues[1], "English") }
                .toList()
        }.getOrDefault(emptyList())

        return playlistUtils.extractFromHls(
            videoSource,
            referer = url,
            videoNameGen = { "AWSStream - $it" },
            subtitleList = subtitles,
        )
    }

    // ============================= Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores category filter"),
        CategoryFilter(),
    )

    private class CategoryFilter :
        UriPartFilter(
            "Category",
            arrayOf(
                Pair("Series", "series"),
                Pair("Movies", "movies"),
                Pair("Cartoon", "category/cartoon"),
                Pair("Anime", "category/anime"),
            ),
        )

    private open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart(): String = vals[max(state, 0)].second
    }

    // ============================= Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL_KEY
            title = "Custom base URL"
            summary = "Leave blank to use Toonstream domain source"
            dialogTitle = "Custom base URL"
            setDefaultValue("")
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { serverPriority(it.quality, server) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ============================= Utilities ==============================

    private fun String.pageUrl(path: String, page: Int): String = if (page <= 1) {
        "$this/$path/"
    } else {
        "$this/$path/page/$page/"
    }

    private fun String.fixUrl(base: String): String = when {
        startsWith("//") -> "https:$this"
        startsWith("http") -> this
        startsWith("/") -> base.toHttpUrl().newBuilder().encodedPath(this).build().toString()
        else -> base.toHttpUrl().resolve(this).toString()
    }

    private fun Element.imageUrl(): String = attr("abs:src")
        .ifBlank { attr("abs:data-src") }
        .ifBlank { attr("abs:content") }
        .ifBlank { attr("src").takeIf { it.startsWith("//") }?.let { "https:$it" }.orEmpty() }

    private fun String.cleanTitle(): String = replace("Watch Online", "").trim()

    private fun String.firstNumber(): Int? = Regex("""\d+""").find(this)?.value?.toIntOrNull()

    private fun String.base(): String = URI(this).let { "${it.scheme}://${it.host}" }

    private fun serverPriority(quality: String, preferredServer: String): Int {
        val server = quality.lowercase()
        if (preferredServer != PREF_SERVER_DEFAULT) {
            val preferred = preferredServer.lowercase()
            return if (
                server.contains(preferred) ||
                (preferred == "mirror" && server.contains("blakiteapi")) ||
                (preferred == "blakiteapi" && server.contains("mirror"))
            ) {
                1
            } else {
                0
            }
        }

        return when {
            server.contains("mirror") || server.contains("blakiteapi") -> 7
            server.contains("filemoon") -> 6
            server.contains("streamwish") || server.contains("filelions") -> 5
            server.contains("vidhide") || server.contains("streamhide") || server.contains("streamhg") -> 4
            server.contains("dood") -> 3
            server.contains("streamruby") -> 2
            server.contains("vidmoly") -> 1
            else -> 0
        }
    }

    @Serializable
    private data class DomainsDto(
        @SerialName("toonstream")
        val toonstream: String,
    )

    @Serializable
    private data class AwsStreamDto(
        @SerialName("videoSource")
        val videoSource: String = "",
    )

    companion object {
        private const val DOMAINS_URL = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
        private const val PREF_BASE_URL_KEY = "custom_base_url"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Auto"
        private val SERVER_LIST = arrayOf(
            "Auto",
            "Mirror",
            "Blakiteapi",
            "FileMoon",
            "StreamWish",
            "VidHide",
            "Dood",
            "StreamRuby",
            "VidMoly",
        )
    }
}
