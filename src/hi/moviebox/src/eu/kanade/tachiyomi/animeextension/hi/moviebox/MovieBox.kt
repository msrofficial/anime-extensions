package eu.kanade.tachiyomi.animeextension.hi.moviebox

import android.net.Uri
import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class MovieBox : AnimeHttpSource() {

    override val name = "MovieBox"

    override val baseUrl = "https://api3.aoneroom.com"

    override val lang = "all"

    override val supportsLatest = true

    private val random = SecureRandom()
    private val deviceId by lazy { ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) } }
    private val signingKey = Base64.decode(
        String(Base64.decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==", Base64.DEFAULT)),
        Base64.DEFAULT,
    )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=8610422883619422240&page=$page&perPage=20"
        return GET(url, apiHeaders("GET", url, accept = "application/json", contentType = "application/json"))
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=8610422883619422240&page=$page&perPage=20"
        return GET(url, apiHeaders("GET", url, accept = "application/json", contentType = "application/json"))
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val entries = listOf("1", "2", "1006").flatMap { channelId ->
            runCatching { fetchListPage(channelId, page) }.getOrDefault(emptyList())
        }.distinctBy { it.url }
        return AnimesPage(entries, entries.isNotEmpty())
    }

    private fun parseListing(response: Response): AnimesPage {
        val root = JSONObject(response.body.string())
        val data = root.optJSONObject("data") ?: return AnimesPage(emptyList(), false)
        val items = data.optJSONArray("items") ?: data.optJSONArray("subjects") ?: data.optJSONArray("categoryList")
        val entries = items?.toObjectList().orEmpty().mapNotNull { it.toAnime() }
        return AnimesPage(entries, entries.isNotEmpty())
    }

    private fun JSONObject.toAnime(): SAnime? {
        val id = optString("subjectId").ifBlank { optString("id") }
        if (id.isBlank()) return null
        return SAnime.create().apply {
            url = "/subject/$id"
            title = optString("title").substringBefore("[").trim()
            thumbnail_url = optJSONObject("cover")?.optString("url")?.ifBlank { null }
            status = SAnime.UNKNOWN
        }
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return popularAnimeRequest(page)
        val body = JSONObject()
            .put("page", page)
            .put("perPage", 20)
            .put("keyword", query)
            .toString()
        val url = "$baseUrl/wefeed-mobile-bff/subject-api/search/v2"
        return POST(url, apiHeaders("POST", url, body = body), body.toRequestBody(JSON_UTF8))
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val root = JSONObject(response.body.string())
        val results = root.optJSONObject("data")?.optJSONArray("results") ?: return AnimesPage(emptyList(), false)
        val entries = mutableListOf<SAnime>()
        results.toObjectList().forEach { group ->
            group.optJSONArray("subjects")?.toObjectList().orEmpty().mapNotNullTo(entries) { it.toAnime() }
        }
        return AnimesPage(entries.distinctBy { it.url }, entries.isNotEmpty())
    }

    private fun fetchListPage(channelId: String, page: Int): List<SAnime> {
        val body = JSONObject()
            .put("page", page)
            .put("perPage", 10)
            .put("channelId", channelId)
            .put("classify", "All")
            .put("country", "All")
            .put("year", "All")
            .put("genre", "All")
            .put("sort", "Latest")
            .toString()
        val url = "$baseUrl/wefeed-mobile-bff/subject-api/list"
        val response = client.newCall(
            POST(url, apiHeaders("POST", url, body = body), body.toRequestBody(JSON_UTF8)),
        ).execute()
        return response.use {
            val data = JSONObject(it.body.string()).optJSONObject("data") ?: return emptyList()
            val items = data.optJSONArray("items") ?: data.optJSONArray("subjects") ?: return emptyList()
            items.toObjectList().mapNotNull { item -> item.toAnime() }
        }
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("/")
        val url = "$baseUrl/wefeed-mobile-bff/subject-api/get?subjectId=$id"
        return GET(url, apiHeaders("GET", url, accept = "application/json", contentType = "application/json"))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val data = JSONObject(response.body.string()).optJSONObject("data") ?: JSONObject()
        return SAnime.create().apply {
            url = "/subject/${data.optString("subjectId")}"
            title = data.optString("title").substringBefore("[").trim()
            thumbnail_url = data.optJSONObject("cover")?.optString("url")?.ifBlank { null }
            description = data.optString("description").ifBlank { null }
            genre = data.optString("genre").ifBlank { null }
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = JSONObject(response.body.string()).optJSONObject("data") ?: return emptyList()
        val id = data.optString("subjectId").ifBlank {
            response.request.url.queryParameter("subjectId").orEmpty()
        }
        val subjectType = data.optInt("subjectType", 1)
        if (subjectType != 2 && subjectType != 7) {
            return listOf(
                SEpisode.create().apply {
                    url = playUrl(id, 0, 0)
                    name = "Movie"
                    episode_number = 1F
                },
            )
        }

        val subjectIds = mutableListOf(id)
        data.optJSONArray("dubs")?.toObjectList().orEmpty().forEach {
            val dubId = it.optString("subjectId")
            if (dubId.isNotBlank() && dubId !in subjectIds) subjectIds += dubId
        }

        val episodeKeys = linkedSetOf<Pair<Int, Int>>()
        subjectIds.forEach { subjectId ->
            val url = "$baseUrl/wefeed-mobile-bff/subject-api/season-info?subjectId=$subjectId"
            runCatching {
                val seasons = client.newCall(GET(url, apiHeaders("GET", url, accept = "application/json", contentType = "application/json")))
                    .execute()
                    .use { JSONObject(it.body.string()) }
                    .optJSONObject("data")
                    ?.optJSONArray("seasons")
                    ?.toObjectList()
                    .orEmpty()
                seasons.forEach { season ->
                    val seasonNumber = season.optInt("se", 1).coerceAtLeast(1)
                    val maxEpisode = season.optInt("maxEp", 1).coerceAtLeast(1)
                    for (episode in 1..maxEpisode) episodeKeys += seasonNumber to episode
                }
            }
        }

        return episodeKeys.map { (season, episode) ->
            SEpisode.create().apply {
                url = playUrl(id, season, episode)
                name = "S${season}E$episode"
                episode_number = episode.toFloat()
            }
        }.ifEmpty {
            listOf(
                SEpisode.create().apply {
                    url = playUrl(id, 1, 1)
                    name = "Episode 1"
                    episode_number = 1F
                },
            )
        }.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, playHeaders("GET", episode.url))

    override fun videoListParse(response: Response): List<Video> {
        val subjectId = response.request.url.queryParameter("subjectId").orEmpty()
        val season = response.request.url.queryParameter("se")?.toIntOrNull() ?: 0
        val episode = response.request.url.queryParameter("ep")?.toIntOrNull() ?: 0
        if (subjectId.isBlank()) return emptyList()

        val subjectUrl = "$baseUrl/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId"
        val subjectResponse = client.newCall(GET(subjectUrl, playHeaders("GET", subjectUrl))).execute()
        val token = subjectResponse.header("x-user")?.let {
            runCatching { JSONObject(it).optString("token").ifBlank { null } }.getOrNull()
        }
        val subjectData = subjectResponse.use { JSONObject(it.body.string()) }.optJSONObject("data")

        val subjectIds = mutableListOf(subjectId to "Original")
        subjectData?.optJSONArray("dubs")?.toObjectList().orEmpty().forEach { dub ->
            val dubId = dub.optString("subjectId")
            val langName = dub.optString("lanName").ifBlank { "Audio" }
            if (dubId.isNotBlank() && subjectIds.none { it.first == dubId }) subjectIds += dubId to langName
        }

        return subjectIds.flatMap { (id, language) ->
            runCatching { videosFromSubject(id, language, season, episode, token) }.getOrDefault(emptyList())
        }.distinctBy { it.videoUrl }
    }

    private fun videosFromSubject(subjectId: String, language: String, season: Int, episode: Int, token: String?): List<Video> {
        val url = "$baseUrl/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=$season&ep=$episode"
        val response = client.newCall(GET(url, playHeaders("GET", url, token))).execute()
        if (!response.isSuccessful) return emptyList()

        val root = response.use { JSONObject(it.body.string()) }
        val streams = root.optJSONObject("data")?.optJSONArray("streams") ?: JSONArray()
        val videos = streams.toObjectList().mapNotNull { stream ->
            val streamUrl = stream.optString("url").ifBlank { return@mapNotNull null }
            val signCookie = stream.optString("signCookie").ifBlank { null }
            val quality = stream.optString("resolutions").qualityLabel()
            val format = stream.optString("format")
            val videoHeaders = Headers.Builder()
                .add("Referer", baseUrl)
                .apply { if (signCookie != null) add("Cookie", signCookie) }
                .build()
            Video(
                streamUrl,
                "$name ${language.replace("dub", "Audio", ignoreCase = true)} - ${quality.ifBlank { format.ifBlank { "Auto" } }}",
                streamUrl,
                headers = videoHeaders,
                subtitleTracks = subtitlesFor(subjectId, stream.optString("id"), token, language),
            )
        }

        return videos.ifEmpty { detectorVideos(subjectId, language, token) }
    }

    private fun detectorVideos(subjectId: String, language: String, token: String?): List<Video> {
        val url = "$baseUrl/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId"
        val data = client.newCall(GET(url, playHeaders("GET", url, token))).execute()
            .use { JSONObject(it.body.string()).optJSONObject("data") }
            ?: return emptyList()
        return data.optJSONArray("resourceDetectors")?.toObjectList().orEmpty().flatMap { detector ->
            detector.optJSONArray("resolutionList")?.toObjectList().orEmpty().mapNotNull { item ->
                val link = item.optString("resourceLink").ifBlank { return@mapNotNull null }
                val quality = item.optInt("resolution", 0).takeIf { it > 0 }?.let { "${it}p" } ?: "Video"
                Video(link, "$name $language - $quality", link, headers = Headers.headersOf("Referer", baseUrl))
            }
        }
    }

    private fun subtitlesFor(subjectId: String, streamId: String, token: String?, language: String): List<Track> {
        if (streamId.isBlank()) return emptyList()
        val urls = listOf(
            "$baseUrl/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$subjectId&streamId=$streamId",
            "$baseUrl/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$subjectId&resourceId=$streamId&episode=0",
        )
        return urls.flatMap { url ->
            runCatching {
                val root = client.newCall(GET(url, playHeaders("GET", url, token, accept = "", contentType = "")))
                    .execute()
                    .use { JSONObject(it.body.string()) }
                root.optJSONObject("data")?.optJSONArray("extCaptions")?.toObjectList().orEmpty().mapNotNull { caption ->
                    val subUrl = caption.optString("url").ifBlank { return@mapNotNull null }
                    val langName = caption.optString("language")
                        .ifBlank { caption.optString("lanName") }
                        .ifBlank { caption.optString("lan") }
                        .ifBlank { "Subtitle" }
                    Track(subUrl, "$langName ($language)")
                }
            }.getOrDefault(emptyList())
        }.distinctBy { it.url }
    }

    // ================================ API =================================

    private fun apiHeaders(
        method: String,
        url: String,
        accept: String = "application/json",
        contentType: String = "application/json; charset=utf-8",
        body: String? = null,
    ): Headers = Headers.Builder()
        .add("user-agent", USER_AGENT)
        .add("accept", accept)
        .add("content-type", contentType)
        .add("connection", "keep-alive")
        .add("x-client-token", xClientToken())
        .add("x-tr-signature", xTrSignature(method, accept, contentType, url, body))
        .add("x-client-info", clientInfo("com.community.mbox.in", "3.0.03.0529.03", 50020042, "IN"))
        .add("x-client-status", "0")
        .add("x-play-mode", "2")
        .build()

    private fun playHeaders(
        method: String,
        url: String,
        token: String? = null,
        accept: String = "application/json",
        contentType: String = "application/json",
    ): Headers = Headers.Builder()
        .apply { if (!token.isNullOrBlank()) add("Authorization", "Bearer $token") }
        .add("user-agent", PLAY_USER_AGENT)
        .add("accept", accept)
        .add("content-type", contentType)
        .add("connection", "keep-alive")
        .add("x-client-token", xClientToken())
        .add("x-tr-signature", xTrSignature(method, accept, contentType, url))
        .add("x-client-info", clientInfo("com.community.oneroom", "3.0.13.0325.03", 50020088, "US"))
        .add("x-client-status", "0")
        .build()

    private fun clientInfo(packageName: String, versionName: String, versionCode: Int, region: String): String {
        val (brand, model) = randomBrandModel()
        return JSONObject().apply {
            put("package_name", packageName)
            put("version_name", versionName)
            put("version_code", versionCode)
            put("os", "android")
            put("os_version", "13")
            put("device_id", deviceId)
            put("install_store", "ps")
            put("gaid", "d7578036d13336cc")
            put("brand", brand)
            put("model", model)
            put("system_language", "en")
            put("net", "NETWORK_WIFI")
            put("region", region)
            put("timezone", "Asia/Calcutta")
            put("sp_code", "")
        }.toString()
    }

    private fun xClientToken(): String {
        val timestamp = System.currentTimeMillis().toString()
        return "$timestamp,${md5(timestamp.reversed().toByteArray())}"
    }

    private fun xTrSignature(method: String, accept: String?, contentType: String?, url: String, body: String? = null): String {
        val timestamp = System.currentTimeMillis()
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val mac = Mac.getInstance("HmacMD5").apply {
            init(SecretKeySpec(signingKey, "HmacMD5"))
        }
        val signature = Base64.encodeToString(mac.doFinal(canonical.toByteArray()), Base64.NO_WRAP)
        return "$timestamp|2|$signature"
    }

    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long,
    ): String {
        val parsed = Uri.parse(url)
        val query = parsed.queryParameterNames.sorted().joinToString("&") { key ->
            parsed.getQueryParameters(key).joinToString("&") { value -> "$key=$value" }
        }
        val canonicalUrl = parsed.path.orEmpty() + if (query.isNotEmpty()) "?$query" else ""
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = bodyBytes?.let { md5(if (it.size > 102400) it.copyOfRange(0, 102400) else it) }.orEmpty()
        val bodyLength = bodyBytes?.size?.toString().orEmpty()
        return "${method.uppercase()}\n${accept.orEmpty()}\n${contentType.orEmpty()}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
    }

    private fun md5(input: ByteArray): String = MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) }

    private fun playUrl(subjectId: String, season: Int, episode: Int): String = "$baseUrl/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=$season&ep=$episode"

    private fun randomBrandModel(): Pair<String, String> {
        val brandModels = mapOf(
            "Samsung" to listOf("SM-S918B", "SM-A528B", "SM-M336B"),
            "Xiaomi" to listOf("2201117TI", "M2012K11AI", "Redmi Note 11"),
            "OnePlus" to listOf("LE2111", "CPH2449", "IN2023"),
            "Google" to listOf("Pixel 6", "Pixel 7", "Pixel 8"),
            "Realme" to listOf("RMX3085", "RMX3360", "RMX3551"),
        )
        val brand = brandModels.keys.random(Random(System.nanoTime()))
        return brand to brandModels.getValue(brand).random(Random(System.nanoTime()))
    }

    private fun JSONArray.toObjectList(): List<JSONObject> = List(length()) { index -> optJSONObject(index) }.filterNotNull()

    private fun String.qualityLabel(): String = when {
        contains("2160") -> "2160p"
        contains("1440") -> "1440p"
        contains("1080") -> "1080p"
        contains("720") -> "720p"
        contains("480") -> "480p"
        contains("360") -> "360p"
        contains("240") -> "240p"
        else -> this
    }

    companion object {
        private val JSON_UTF8 = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT = "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)"
        private const val PLAY_USER_AGENT = "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Pixel 7; Build/TQ3A.230901.001; Cronet/145.0.7582.0)"
    }
}
