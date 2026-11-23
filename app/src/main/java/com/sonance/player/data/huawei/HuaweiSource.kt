package com.sonance.player.data.huawei

import com.sonance.player.data.OnlineSource
import com.sonance.player.data.RemotePlaylist
import com.sonance.player.data.RemotePlaylistPage
import com.sonance.player.data.RemoteSearchPage
import com.sonance.player.data.RemoteTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Huawei Music online source.
 *
 * Read endpoints and payload shapes are ported from the Huawei desktop client in this repo
 * (`huawei-music-android/web/src`): search/public playlist are anonymous, while favourites,
 * personal playlists, history and playback need the music session obtained after OAuth login.
 *
 * Virtual playlist ids (`huawei:liked` / `huawei:history` / `huawei:daily`) let Sonance's
 * existing playlist detail page show account data without changing its UI.
 */
object HuaweiSource : OnlineSource {

    override val id = "huawei"
    override val label = "华为音乐"

    const val VIRTUAL_LIKED = "huawei:liked"
    const val VIRTUAL_HISTORY = "huawei:history"
    const val VIRTUAL_DAILY = "huawei:daily"

    private const val SEARCH_TTL = 10 * 60_000L
    private const val PLAYLIST_TTL = 30 * 60_000L
    private const val RECOMMEND_TTL = 30 * 60_000L
    private const val STREAM_TTL = 5 * 60_000L
    private const val ACCOUNT_TTL = 20 * 60_000L
    private const val DAILY_TTL = 12 * 60 * 60_000L
    private const val GUESS_TTL = 12 * 60 * 60_000L
    private const val FM_TTL = 10 * 60_000L

    @Volatile
    private var lastError: String? = null

    // ---------------------------------------------------------------- search / public

    override suspend fun search(query: String, limit: Int, offset: Int): RemoteSearchPage? =
        withContext(Dispatchers.IO) {
            runCatching {
                val cached = HuaweiCache.get("search:$query:$limit:$offset")
                val root = if (cached != null) JSONObject(cached) else {
                    val body = JSONObject()
                        .put("contentType", "1")
                        .put("correct", "1")
                        .put("queryWord", query.trim().take(80))
                        .put("start", offset.toString())
                        .put("limit", limit.toString())
                    HuaweiHttp.postJson("/music-search-service/v10/service/fuzzysearch", body).also {
                        HuaweiCache.put("search:$query:$limit:$offset", it.toString(), SEARCH_TTL)
                    }
                }
                val songs = root.optJSONArray("songSimpleInfos") ?: JSONArray()
                val tracks = buildList {
                    for (i in 0 until songs.length()) trackFrom(songs.optJSONObject(i))?.let(::add)
                }
                RemoteSearchPage(tracks, root.optString("totalNum").toIntOrNull() ?: -1)
            }.getOrNull()
        }

    override suspend fun searchPlaylists(query: String, limit: Int, offset: Int): RemotePlaylistPage? =
        withContext(Dispatchers.IO) {
            runCatching {
                val cached = HuaweiCache.get("plsearch:$query:$limit:$offset")
                val root = if (cached != null) JSONObject(cached) else {
                    val body = JSONObject()
                        .put("contentType", "4")
                        .put("correct", "1")
                        .put("queryWord", query.trim().take(80))
                        .put("start", offset.toString())
                        .put("limit", limit.toString())
                    HuaweiHttp.postJson("/music-search-service/v10/service/fuzzysearch", body).also {
                        HuaweiCache.put("plsearch:$query:$limit:$offset", it.toString(), SEARCH_TTL)
                    }
                }
                val lists = root.optJSONArray("musicListSimpleInfos") ?: JSONArray()
                val playlists = buildList {
                    for (i in 0 until lists.length()) playlistFrom(lists.optJSONObject(i))?.let(::add)
                }
                RemotePlaylistPage(playlists, root.optString("totalNum").toIntOrNull() ?: -1)
            }.getOrNull()
        }

    override suspend fun recommendedPlaylists(limit: Int): List<RemotePlaylist>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val personal = userPlaylists()?.distinctBy { it.name }.orEmpty()
                if (personal.isNotEmpty()) personal.take(limit)
                else searchPlaylists("热门歌单", limit, 0)?.playlists.orEmpty()
            }.getOrNull()
        }

    // ---------------------------------------------------------------- account library

    /** 华为账号收藏的歌曲。接口返回 [favoriteInfos]，不是 contentSimpleInfos。 */
    suspend fun favoriteTracks(limit: Int = 500): List<RemoteTrack>? = withContext(Dispatchers.IO) {
        val acc = HuaweiAuth.account ?: return@withContext null
        val key = "favorites:${acc.snsUserId}:$limit"
        HuaweiCache.get(key)?.let { return@withContext parseTrackArray(JSONArray(it)) }
        runCatching {
            val root = HuaweiHttp.postJson(
                "/music-userbehavior-service/v3/service/favorite/simple/all",
                JSONObject()
                    .put("targetSnsUserID", acc.snsUserId)
                    .put("start", 0)
                    .put("limit", limit)
                    .put("contentTypes", JSONArray().put("1")),
                authenticated = true
            )
            val items = root.optJSONArray("favoriteInfos")
                ?: root.optJSONArray("contentSimpleInfos")
                ?: JSONArray()
            buildList {
                for (i in 0 until items.length()) trackFrom(items.optJSONObject(i))?.let(::add)
            }.distinctBy { it.remoteId }.also {
                HuaweiCache.put(key, JSONArray(it.map(::trackToJson)).toString(), ACCOUNT_TTL)
            }
        }.getOrNull()
    }

    /** 华为服务端的“最近播放”，只读。 */
    suspend fun historyTracks(): List<RemoteTrack>? = withContext(Dispatchers.IO) {
        val acc = HuaweiAuth.account ?: return@withContext null
        val key = "history:${acc.snsUserId}"
        HuaweiCache.get(key)?.let { return@withContext parseTrackArray(JSONArray(it)) }
        runCatching {
            val root = HuaweiHttp.getJson(
                "/music-userbehavior-service/v1/service/recentlyplayed/query?contentType=1",
                authenticated = true
            )
            val items = root.optJSONArray("contentSimpleInfos") ?: JSONArray()
            buildList {
                for (i in 0 until items.length()) trackFrom(items.optJSONObject(i))?.let(::add)
            }.distinctBy { it.remoteId }.also {
                HuaweiCache.put(key, JSONArray(it.map(::trackToJson)).toString(), ACCOUNT_TTL)
            }
        }.getOrNull()
    }

    /**
     * 账号收藏 / 自建 / 榜单歌单。
     *
     * 华为这个发现接口对 contentTypes 的类型很敏感：有的网关只吃数字、有的只吃字符串，
     * 还有的账号只有在单独查某个 type 时才会把「我创建的歌单」物化出来。桌面版因此把
     * 合并数字、合并字符串、单类型数字、单类型字符串全部试一遍。这里照做，并且把
     * 缺失的 type 也单独补查 —— 之前只查字符串合并，导致部分账号的自建歌单读不到。
     */
    suspend fun userPlaylists(): List<RemotePlaylist>? = withContext(Dispatchers.IO) {
        val acc = HuaweiAuth.account ?: return@withContext null
        val key = "library:v3:${acc.snsUserId}"
        val legacyKey = "library:${acc.snsUserId}"

        fun readCache(cacheKey: String): List<RemotePlaylist>? =
            HuaweiCache.get(cacheKey)?.let { cached ->
                runCatching { parsePlaylistArray(JSONArray(cached)) }.getOrNull()?.takeIf { it.isNotEmpty() }
            }

        readCache(key)?.let { return@withContext it }

        val parsed = runCatching {
            val entries = mutableListOf<JSONObject>()
            val seen = mutableSetOf<String>()
            suspend fun pull(types: List<Any>): Int {
                val root = HuaweiHttp.postJson(
                    "/music-userbehavior-service/v3/service/favorite/simple/all",
                    JSONObject()
                        .put("targetSnsUserID", acc.snsUserId)
                        .put("start", 0)
                        .put("limit", 999)
                        .put("contentTypes", JSONArray().apply { types.forEach { put(it) } }),
                    authenticated = true
                )
                val items = root.optJSONArray("favoriteInfos")
                    ?: root.optJSONArray("contentSimpleInfos")
                    ?: JSONArray()
                var fresh = 0
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val type = contentTypeOf(item)
                    val contentId = item.optString("contentID")
                        .ifBlank { item.optString("contentId") }
                        .ifBlank { item.optString("playListID") }
                        .ifBlank { item.optString("musicListID") }
                    if (contentId.isBlank()) continue
                    val dedupe = "$type:$contentId"
                    if (!seen.add(dedupe)) continue
                    entries += item
                    fresh += 1
                }
                return fresh
            }

            // 1) 合并查询：数字、字符串各试一次（两种网关都见过）。
            runCatching { pull(listOf(1, 4, 5, 7)) }
            if (entries.none { contentTypeOf(it) !in setOf("", "1") }) {
                runCatching { pull(listOf("1", "4", "5", "7")) }
            }

            // 2) 缺哪个 type 就单独补哪个：5=我创建的、4=收藏、7=榜单。
            //    单类型同时试数字和字符串，避免继续漏「我创建的歌单」。
            val presentTypes = entries.map { contentTypeOf(it) }.toSet()
            for (type in listOf("5", "4", "7")) {
                if (type in presentTypes) continue
                runCatching { pull(listOf(type.toInt())) }
                if (type !in entries.map { contentTypeOf(it) }.toSet()) {
                    runCatching { pull(listOf(type)) }
                }
            }

            buildList {
                for (item in entries) {
                    val type = contentTypeOf(item)
                    if (type == "1") continue
                    val real = item.optString("contentID")
                        .ifBlank { item.optString("contentId") }
                        .ifBlank { item.optString("playListID") }
                        .ifBlank { item.optString("musicListID") }
                    if (real.isBlank()) continue
                    val base = playlistFrom(item) ?: continue
                    val prefix = when (type) {
                        "5" -> "huawei:own:"
                        "7" -> "huawei:chart:"
                        else -> "huawei:col:"
                    }
                    add(
                        base.copy(
                            // 带上曲目总数，详情页可以按总数并行分页，不必一页页试探。
                            remoteId = "$prefix${base.trackCount}:$real",
                            creatorName = base.creatorName.ifBlank { acc.nickname }
                        )
                    )
                }
            }.distinctBy { it.remoteId }
        }.getOrNull()

        if (!parsed.isNullOrEmpty()) {
            HuaweiCache.put(key, JSONArray(parsed.map(::playlistToJson)).toString(), RECOMMEND_TTL)
            return@withContext parsed
        }
        // 新接口这次没拿到（网络/网关抖动）时，回退到上个版本留下的旧库缓存，
        // 至少不让已经能看到的自建歌单突然消失；下一次刷新会再尝试新接口。
        readCache(legacyKey)?.also {
            HuaweiCache.put(key, JSONArray(it.map(::playlistToJson)).toString(), 3 * 60_000L)
        }
    }

    fun likedPlaylistOf(tracks: List<RemoteTrack>): RemotePlaylist = RemotePlaylist(
        remoteId = VIRTUAL_LIKED,
        name = "我喜欢的",
        coverUrl = tracks.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl,
        trackCount = tracks.size,
        creatorName = HuaweiAuth.account?.nickname ?: "华为音乐"
    )

    fun historyPlaylistOf(tracks: List<RemoteTrack>): RemotePlaylist = RemotePlaylist(
        remoteId = VIRTUAL_HISTORY,
        name = "播放历史",
        coverUrl = tracks.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl,
        trackCount = tracks.size,
        creatorName = "华为账号 · 最近播放"
    )

    fun dailyPlaylistOf(tracks: List<RemoteTrack>): RemotePlaylist = RemotePlaylist(
        remoteId = VIRTUAL_DAILY,
        name = "每日私人推荐",
        coverUrl = tracks.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl,
        trackCount = tracks.size,
        creatorName = "根据收藏、歌单与历史生成"
    )

    /** 榜单歌单（公开搜索，不依赖账号）。 */
    suspend fun chartPlaylists(limit: Int = 6): List<RemotePlaylist>? = withContext(Dispatchers.IO) {
        runCatching {
            val result = searchPlaylists("热门榜单", limit, 0)?.playlists.orEmpty()
            result.distinctBy { it.name }.take(limit)
        }.getOrNull()
    }

    /** 把收藏/取消收藏同步回华为账号，并让当天缓存失效。 */
    suspend fun setFavorite(remoteId: String, favorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        val acc = HuaweiAuth.account ?: return@withContext false
        runCatching {
            HuaweiHttp.postJson(
                "/music-userbehavior-service/v1/service/favorite/" + if (favorite) "create" else "delete",
                JSONObject().put("contentType", "1").put("contentID", remoteId),
                authenticated = true,
                timeoutMs = 15_000
            )
            // 空值的 0ms TTL 等价于立即失效
            HuaweiCache.put("favorites:${acc.snsUserId}:500", "", 0)
            HuaweiCache.put("library:${acc.snsUserId}", "", 0)
            HuaweiCache.put("library:v3:${acc.snsUserId}", "", 0)
            HuaweiCache.put("fm:${acc.snsUserId}:20", "", 0)
            HuaweiCache.put("guess:${acc.snsUserId}:120", "", 0)
            val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            HuaweiCache.put("daily:${acc.snsUserId}:$day", "", 0)
            true
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- recommendation

    /**
     * 华为官方“日推”端点对外不可用（旧客户端验证过 HTTP 400），桌面端一直用本地口味模型：
     * 从收藏 / 歌单 / 历史里提取常听歌手、专辑、语种、标题词，再搜索候选并打分。
     * 这里保留同一思路的轻量版，结果按账号 + 日期缓存，同一天内保持稳定。
     */
    suspend fun dailyRecommendations(force: Boolean = false): List<RemoteTrack>? = withContext(Dispatchers.IO) {
        val acc = HuaweiAuth.account ?: return@withContext null
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val key = "daily:${acc.snsUserId}:$day"
        if (!force) HuaweiCache.get(key)?.let { return@withContext parseTrackArray(JSONArray(it)) }

        val favorites = favoriteTracks(limit = 500).orEmpty()
        val history = historyTracks().orEmpty()
        val seeds = (favorites + history).distinctBy { it.remoteId }
        val exclude = seeds.map { it.remoteId }.toSet()

        val artistCounts = linkedMapOf<String, Int>()
        val albumSet = mutableSetOf<String>()
        var durationSum = 0L
        var durationCount = 0
        for (track in seeds) {
            for (artist in splitArtists(track.artist)) {
                artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
            }
            if (track.album.isNotBlank()) albumSet += track.album.trim()
            if (track.durationMs > 0) {
                durationSum += track.durationMs
                durationCount += 1
            }
        }
        val avgDuration = if (durationCount > 0) durationSum / durationCount else 0L
        val dominantLanguage = seeds.groupingBy { languageOf(it) }.eachCount().maxByOrNull { it.value }?.key
        val titleTokens = seeds.flatMap { tokensOf(it.title) }.groupingBy { it }.eachCount()

        val candidates = mutableListOf<RemoteTrack>()
        val topArtists = artistCounts.entries.sortedByDescending { it.value }.take(5).map { it.key }
        val topAlbums = albumSet.take(3)
        for (artist in topArtists) {
            search(artist, 30, 0)?.tracks?.let { candidates += it }
        }
        for (album in topAlbums) {
            search(album, 20, 0)?.tracks?.let { candidates += it }
        }
        if (candidates.isEmpty()) {
            // 新账号没有收藏/历史：直接用搜索兜底，避免为了日推去全量下载大歌单。
            search("华语流行", 50, 0)?.tracks?.let { candidates += it }
        }

        val scored = candidates.asSequence()
            .filter { it.remoteId !in exclude }
            .filter { it.playable && !isAccompaniment(it.title) }
            .distinctBy { it.remoteId }
            .map { track ->
                var score = 0.0
                val artists = splitArtists(track.artist)
                for (artist in artists) {
                    val count = artistCounts[artist] ?: artistCounts.entries
                        .firstOrNull { it.key.contains(artist) || artist.contains(it.key) }?.value
                    if (count != null) score += 55.0 + count * 6
                }
                if (track.album.isNotBlank() && albumSet.contains(track.album.trim())) score += 14
                if (dominantLanguage != null && languageOf(track) == dominantLanguage) score += 10
                val overlap = tokensOf(track.title).count { (titleTokens[it] ?: 0) >= 2 }
                score += minOf(12, overlap * 4)
                if (avgDuration > 0 && track.durationMs > 0) {
                    val diff = abs(track.durationMs - avgDuration)
                    score += maxOf(0.0, 6.0 - diff / 60_000.0)
                }
                track to score
            }
            .sortedWith(compareByDescending<Pair<RemoteTrack, Double>> { it.second }
                .thenByDescending { it.first.coverUrl != null }
                .thenBy { it.first.remoteId })
            .toList()

        // 同一歌手最多 3 首，避免一个歌手刷满整页。
        val picked = mutableListOf<RemoteTrack>()
        val artistUsed = mutableMapOf<String, Int>()
        for ((track, _) in scored) {
            val keyArtist = splitArtists(track.artist).firstOrNull() ?: ""
            val used = artistUsed[keyArtist] ?: 0
            if (used >= 3) continue
            artistUsed[keyArtist] = used + 1
            picked += track
            if (picked.size >= 40) break
        }
        HuaweiCache.put(key, JSONArray(picked.map(::trackToJson)).toString(), DAILY_TTL)
        picked
    }

    /**
     * 猜你喜欢：桌面版「猜你喜欢」算法（recommend.mjs v5）的 Android 移植。
     *
     * 种子权重：收藏 1.0、收藏/自建歌单 0.7、最近播放 0.5。候选来自常听歌手/专辑、
     * 二跳歌手/专辑、榜单和歌单共现；按歌手/专辑/语种/主题词/共现/时长/版本七维打分，
     * 学一条接受线，低于线的丢弃，再用 1→2→3→不限 的歌手/专辑上限做多样性。
     *
     * 结果是稳定排序，绝不 `shuffled()`：之前 FM 每次打乱顺序，队列和列表对不上，
     * 表现就是「播放错乱」。返回池的 20 首一批，[batch] 可翻批。
     */
    suspend fun guessYouLike(limit: Int = 20, batch: Int = 0): List<RemoteTrack>? = withContext(Dispatchers.IO) {
        val acc = HuaweiAuth.account ?: return@withContext null
        val key = "guess:${acc.snsUserId}:120"
        val pool = HuaweiCache.get(key)?.let { parseTrackArray(JSONArray(it)) }
            ?: buildGuessYouLikePool().also {
                if (it.isNotEmpty()) {
                    HuaweiCache.put(key, JSONArray(it.map(::trackToJson)).toString(), GUESS_TTL)
                }
            }
        if (pool.isEmpty()) return@withContext null
        val size = limit.coerceIn(1, 50)
        val pages = ((pool.size + size - 1) / size).coerceAtLeast(1)
        val page = ((batch % pages) + pages) % pages
        pool.drop(page * size).take(size).ifEmpty { pool.take(size) }
    }

    /** 兼容旧调用：私人 FM 已改为桌面版「猜你喜欢」，不再随机打散。 */
    suspend fun personalFm(limit: Int = 20): List<RemoteTrack>? = guessYouLike(limit = limit)

    private suspend fun buildGuessYouLikePool(): List<RemoteTrack> = withContext(Dispatchers.IO) {
        val favorites = favoriteTracks(limit = 500).orEmpty()
        val history = historyTracks().orEmpty()
        val lists = userPlaylists().orEmpty()

        val collectionLists = lists.filter {
            it.remoteId.startsWith("huawei:own:") || it.remoteId.startsWith("huawei:col:")
        }.take(3)
        val libraryCharts = lists.filter { it.remoteId.startsWith("huawei:chart:") }.take(2)

        // 只取每个歌单前 50/100 首，和桌面版一样控制首屏耗时；推荐不是歌单同步。
        val playlistPages = coroutineScope {
            collectionLists.map { async(Dispatchers.IO) { playlistPreviewTracks(it.remoteId, 60) } }.awaitAll()
        }
        val playlistTracks = playlistPages.flatten()
        val fallbackCharts = if (libraryCharts.isEmpty()) chartPlaylists(limit = 2).orEmpty() else emptyList()
        val chartLists = (libraryCharts + fallbackCharts).distinctBy { it.remoteId }.take(2)
        val chartTracks = coroutineScope {
            chartLists.map { async(Dispatchers.IO) { playlistPreviewTracks(it.remoteId, 60) } }.awaitAll()
        }.flatten()

        val seeds = mutableListOf<HuaweiRecommender.Seed>()
        val seenSeeds = mutableSetOf<String>()
        fun addSeeds(tracks: List<RemoteTrack>, weight: Double) {
            for (track in tracks) {
                if (track.remoteId.isBlank() || !seenSeeds.add(track.remoteId)) continue
                seeds += HuaweiRecommender.Seed(track, weight)
            }
        }
        addSeeds(favorites, 1.0)
        addSeeds(playlistTracks, 0.7)
        addSeeds(history, 0.5)

        if (seeds.isEmpty()) return@withContext emptyList()

        val profile = HuaweiRecommender.buildProfile(seeds)
        val themes = HuaweiRecommender.buildThemeTokens(seeds, minCount = 2.0, limit = 12)
        val playlistHits = playlistTracks.groupingBy { it.remoteId }.eachCount()
        val model = HuaweiRecommender.Model(profile, playlistHits, themes)
        val scoreProfile = HuaweiRecommender.buildScoreProfile(seeds, model)

        val directArtistKeys = favorites.flatMap { HuaweiRecommender.splitArtists(it.artist) }
            .map(HuaweiRecommender::artistKey).toSet()
        val directAlbumKeys = favorites.map { HuaweiRecommender.albumKey(it.album) }
            .filter { it.isNotBlank() }.toSet()

        // 二跳：歌单/历史里出现、但没被直接收藏的歌手与专辑，用来跳出常听歌手回音室。
        val secondArtists = linkedMapOf<String, Int>()
        val secondAlbums = linkedMapOf<String, Int>()
        for (track in playlistTracks + history) {
            for (artist in HuaweiRecommender.splitArtists(track.artist)) {
                val key = HuaweiRecommender.artistKey(artist)
                if (key !in directArtistKeys) secondArtists[key] = (secondArtists[key] ?: 0) + 1
            }
            if (track.album.isNotBlank() && !HuaweiRecommender.isAccompaniment(track)) {
                val key = HuaweiRecommender.albumKey(track.album)
                if (key !in directAlbumKeys) secondAlbums[key] = (secondAlbums[key] ?: 0) + 1
            }
        }

        val artistSeeds = profile.artistWeights.entries.take(4).map { it.key }
        val albumSeeds = profile.albumWeights.entries.take(3).map { it.key }
        val secondArtistSeeds = secondArtists.entries.sortedByDescending { it.value }.take(3).map { it.key }
        val secondAlbumSeeds = secondAlbums.entries.sortedByDescending { it.value }.take(2).map { it.key }

        val directArtistCandidates = coroutineScope {
            artistSeeds.map { async(Dispatchers.IO) { search(it, 30, 0)?.tracks.orEmpty() } }.awaitAll()
        }.flatten()
        val directAlbumCandidates = coroutineScope {
            albumSeeds.map { async(Dispatchers.IO) { search(it, 30, 0)?.tracks.orEmpty() } }.awaitAll()
        }.flatten()
        val secondArtistCandidates = coroutineScope {
            secondArtistSeeds.map { async(Dispatchers.IO) { search(it, 20, 0)?.tracks.orEmpty() } }.awaitAll()
        }.flatten()
        val secondAlbumCandidates = coroutineScope {
            secondAlbumSeeds.map { async(Dispatchers.IO) { search(it, 20, 0)?.tracks.orEmpty() } }.awaitAll()
        }.flatten()

        val favoriteIds = favorites.map { it.remoteId }.toSet()
        val coOccurrence = playlistTracks.filter { it.remoteId !in favoriteIds }
        val excludeIds = favoriteIds + history.map { it.remoteId }
        val anchored = directArtistCandidates + directAlbumCandidates +
            secondArtistCandidates + secondAlbumCandidates + chartTracks + coOccurrence

        var pool = HuaweiRecommender.rank(
            candidates = anchored,
            model = model,
            scoreProfile = scoreProfile,
            excludeIds = excludeIds,
            limit = 120,
        )
        // 精准池不够时，桌面版会切到「发现新歌」：额外跑主题搜索并把接受线放宽到 0.55。
        if (pool.size < 40) {
            val keywords = HuaweiRecommender.searchKeywords(profile, themes, limit = 3)
            val keywordCandidates = coroutineScope {
                keywords.map { async(Dispatchers.IO) { search(it, 30, 0)?.tracks.orEmpty() } }.awaitAll()
            }.flatten()
            pool = HuaweiRecommender.rank(
                candidates = anchored + keywordCandidates,
                model = model,
                scoreProfile = scoreProfile,
                excludeIds = excludeIds,
                limit = 120,
                floorScale = 0.55,
            )
        }
        pool
    }

    /** 歌单预览：推荐只需要前几十首，绝不为了猜你喜欢同步一个 1811 首的大歌单。 */
    private suspend fun playlistPreviewTracks(playlistId: String, limit: Int): List<RemoteTrack> {
        return when {
            playlistId.startsWith("huawei:own:") -> {
                val ref = parsePlaylistRef(playlistId, "huawei:own:")
                fetchPersonalPage(ref.id, 0, limit.coerceAtMost(50))
                    ?: fetchPublicPage(ref.id, 0, limit.coerceAtMost(100))
                    ?: emptyList()
            }
            playlistId.startsWith("huawei:col:") || playlistId.startsWith("huawei:chart:") -> {
                val prefix = if (playlistId.startsWith("huawei:col:")) "huawei:col:" else "huawei:chart:"
                val ref = parsePlaylistRef(playlistId, prefix)
                fetchPublicPage(ref.id, 0, limit.coerceAtMost(100))
                    ?: fetchPersonalPage(ref.id, 0, limit.coerceAtMost(50))
                    ?: emptyList()
            }
            else -> {
                fetchPublicPage(playlistId, 0, limit.coerceAtMost(100))
                    ?: fetchPersonalPage(playlistId, 0, limit.coerceAtMost(50))
                    ?: emptyList()
            }
        }.distinctBy { it.remoteId }
    }

    // ---------------------------------------------------------------- playlist detail

    override suspend fun playlistTracks(playlistId: String): List<RemoteTrack>? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                playlistId == VIRTUAL_LIKED -> favoriteTracks()
                playlistId == VIRTUAL_HISTORY -> historyTracks()
                playlistId == VIRTUAL_DAILY -> dailyRecommendations()
                playlistId.startsWith("huawei:own:") -> {
                    val ref = parsePlaylistRef(playlistId, "huawei:own:")
                    personalPlaylistTracks(ref.id, ref.count) ?: publicPlaylistTracks(ref.id, ref.count)
                }
                playlistId.startsWith("huawei:col:") || playlistId.startsWith("huawei:chart:") -> {
                    val prefix = if (playlistId.startsWith("huawei:col:")) "huawei:col:" else "huawei:chart:"
                    val ref = parsePlaylistRef(playlistId, prefix)
                    publicPlaylistTracks(ref.id, ref.count) ?: personalPlaylistTracks(ref.id, ref.count)
                }
                else -> publicPlaylistTracks(playlistId, 0) ?: personalPlaylistTracks(playlistId, 0)
            }
        }.getOrNull()
    }

    private data class PlaylistRef(val id: String, val count: Int)

    /** 兼容 `huawei:own:1811:abc` 与旧版 `huawei:own:abc` 两种写法。 */
    private fun parsePlaylistRef(raw: String, prefix: String): PlaylistRef {
        val body = raw.removePrefix(prefix)
        val first = body.substringBefore(':', "")
        val count = first.toIntOrNull()
        return if (count != null && body.contains(':')) {
            PlaylistRef(body.substringAfter(':'), count)
        } else {
            PlaylistRef(body, 0)
        }
    }

    /** 公开歌单：按总数并行分页；不知道总数时逐页读到短页为止。 */
    private suspend fun publicPlaylistTracks(playlistId: String, expectedCount: Int): List<RemoteTrack>? {
        val cacheKey = "pltracks:public:$playlistId"
        HuaweiCache.get(cacheKey)?.let { return parseTrackArray(JSONArray(it)) }
        val limit = 100
        val all = mutableListOf<RemoteTrack>()
        var complete = true

        if (expectedCount > 0) {
            val pages = ((expectedCount + limit - 1) / limit).coerceAtMost(60)
            var page = 0
            while (page < pages) {
                val batch = (page until minOf(pages, page + 4)).toList()
                val results = coroutineScope {
                    batch.map { p ->
                        async(Dispatchers.IO) { fetchPublicPage(playlistId, p * limit, limit) }
                    }.awaitAll()
                }
                for ((offset, result) in batch.zip(results)) {
                    if (result == null) { complete = false; break }
                    if (result.isEmpty()) { page = pages; break }
                    all += result
                    if (result.size < limit) { page = pages; break }
                }
                if (!complete) break
                page += batch.size
            }
        } else {
            var start = 0
            var page = 0
            while (page < 40) {
                val result = fetchPublicPage(playlistId, start, limit)
                if (result == null) { complete = false; break }
                if (result.isEmpty()) break
                all += result
                if (result.size < limit) break
                start += result.size
                page += 1
            }
        }
        val deduped = all.distinctBy { it.remoteId }
        // 只有完整读取才用长 TTL；因网络/接口失败中断的用短 TTL，用户刷新可继续补。
        HuaweiCache.put(cacheKey, JSONArray(deduped.map(::trackToJson)).toString(),
            if (complete) PLAYLIST_TTL else 3 * 60_000L)
        return deduped.takeIf { it.isNotEmpty() }
    }

    private suspend fun fetchPublicPage(playlistId: String, start: Int, limit: Int): List<RemoteTrack>? {
        repeat(2) {
            runCatching {
                val body = JSONObject()
                    .put("musicListID", playlistId)
                    .put("copyrightSwitch", "1")
                    .put("start", start)
                    .put("limit", limit)
                val root = HuaweiHttp.postJson(
                    "/music-operation-service/v2/service/musiclist/detail/bymusiclistid", body
                )
                val songs = root.optJSONObject("musicListInfoEx")?.optJSONArray("songSimpleInfos")
                    ?: JSONArray()
                return buildList {
                    for (i in 0 until songs.length()) trackFrom(songs.optJSONObject(i))?.let(::add)
                }
            }
        }
        return null
    }

    /** 自建歌单走 account-scoped 的 playlist/detail/all（GET），大歌单按总数并行分页。 */
    private suspend fun personalPlaylistTracks(playlistId: String, expectedCount: Int): List<RemoteTrack>? {
        if (HuaweiAuth.account == null) return null
        val cacheKey = "pltracks:own:$playlistId"
        HuaweiCache.get(cacheKey)?.let { return parseTrackArray(JSONArray(it)) }
        val limit = 50
        val all = mutableListOf<RemoteTrack>()
        var complete = true

        if (expectedCount > 0) {
            val pages = ((expectedCount + limit - 1) / limit).coerceAtMost(60)
            var page = 0
            while (page < pages) {
                val batch = (page until minOf(pages, page + 4)).toList()
                val results = coroutineScope {
                    batch.map { p ->
                        async(Dispatchers.IO) { fetchPersonalPage(playlistId, p * limit, limit) }
                    }.awaitAll()
                }
                for ((offset, result) in batch.zip(results)) {
                    if (result == null) { complete = false; break }
                    if (result.isEmpty()) { page = pages; break }
                    all += result
                    if (result.size < limit) { page = pages; break }
                }
                if (!complete) break
                page += batch.size
            }
        } else {
            var start = 0
            var page = 0
            while (page < 60) {
                val result = fetchPersonalPage(playlistId, start, limit)
                if (result == null) { complete = false; break }
                if (result.isEmpty()) break
                all += result
                if (result.size < limit) break
                start += result.size
                page += 1
            }
        }
        val deduped = all.distinctBy { it.remoteId }
        HuaweiCache.put(cacheKey, JSONArray(deduped.map(::trackToJson)).toString(),
            if (complete) PLAYLIST_TTL else 3 * 60_000L)
        return deduped.takeIf { it.isNotEmpty() }
    }

    private suspend fun fetchPersonalPage(playlistId: String, start: Int, limit: Int): List<RemoteTrack>? {
        repeat(2) {
            runCatching {
                val encoded = URLEncoder.encode(playlistId, "UTF-8")
                val root = HuaweiHttp.getJson(
                    "/music-userbehavior-service/v1/service/playlist/detail/all" +
                        "?start=$start&limit=$limit&playListID=$encoded",
                    authenticated = true
                )
                val songs = root.optJSONObject("playListInfoEx")?.optJSONArray("playListSongInfoList")
                    ?: JSONArray()
                return buildList {
                    for (i in 0 until songs.length()) trackFrom(songs.optJSONObject(i))?.let(::add)
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------- playback

    override fun streamUrl(remoteId: String): String = ""

    override suspend fun resolveStream(remoteId: String): String? = withContext(Dispatchers.IO) {
        if (HuaweiAuth.account == null) {
            lastError = "请先登录华为音乐账号"
            return@withContext null
        }
        HuaweiCache.get("stream:$remoteId")?.let { return@withContext it }
        runCatching {
            val root = HuaweiHttp.postJson(
                "/music-play-service/v3/service/file/bycontentcode",
                JSONObject()
                    .put("contentCode", remoteId)
                    .put("contentType", "1")
                    .put("qualityType", "2"),
                authenticated = true
            )
            val url = root.optString("fileURL").takeIf { it.startsWith("http") }
                ?: throw IllegalStateException("华为音乐未返回播放地址")
            HuaweiCache.put("stream:$remoteId", url, STREAM_TTL)
            lastError = null
            url
        }.getOrElse {
            lastError = it.message ?: "华为音乐取流失败"
            null
        }
    }

    override fun lastStreamError(remoteId: String): String? = lastError

    override fun streamQualityLabel(remoteId: String): String? = "HQ"

    override fun streamFormat(remoteId: String): String? =
        HuaweiCache.get("stream:$remoteId")?.let { url ->
            when {
                url.contains(".flac", true) -> "flac"
                url.contains(".m4a", true) -> "m4a"
                url.contains(".mp3", true) -> "mp3"
                else -> null
            }
        }

    override fun thumbUrl(url: String, px: Int): String = url

    // ---------------------------------------------------------------- mapping

    private fun parsePlaylistArray(items: JSONArray): List<RemotePlaylist> = buildList {
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val name = o.optString("name")
            if (id.isBlank() || name.isBlank()) continue
            add(
                RemotePlaylist(
                    remoteId = id,
                    name = name,
                    coverUrl = o.optString("cover").takeIf { it.startsWith("http") },
                    trackCount = o.optInt("count"),
                    creatorName = o.optString("creator")
                )
            )
        }
    }

    private fun playlistToJson(p: RemotePlaylist): JSONObject = JSONObject()
        .put("id", p.remoteId)
        .put("name", p.name)
        .put("cover", p.coverUrl ?: "")
        .put("count", p.trackCount)
        .put("creator", p.creatorName)

    private fun parseTrackArray(items: JSONArray): List<RemoteTrack> = buildList {
        for (i in 0 until items.length()) trackFromJson(items.optJSONObject(i))?.let(::add)
    }

    private fun trackToJson(t: RemoteTrack): JSONObject = JSONObject()
        .put("id", t.remoteId)
        .put("title", t.title)
        .put("artist", t.artist)
        .put("album", t.album)
        .put("duration", t.durationMs)
        .put("cover", t.coverUrl ?: "")
        .put("playable", t.playable)

    private fun trackFromJson(o: JSONObject?): RemoteTrack? {
        if (o == null) return null
        val id = o.optString("id")
        if (id.isBlank()) return null
        return RemoteTrack(
            remoteId = id,
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            durationMs = o.optLong("duration"),
            coverUrl = o.optString("cover").takeIf { it.startsWith("http") },
            playable = o.optBoolean("playable", true),
            vip = false
        )
    }

    private fun trackFrom(o: JSONObject?): RemoteTrack? {
        if (o == null) return null
        val id = o.optString("contentID").ifBlank { o.optString("contentId") }
            .ifBlank { o.optString("songCode") }
        val title = o.optString("contentName").ifBlank { o.optString("songName") }
        if (id.isBlank() || title.isBlank()) return null
        val artist = o.optString("artistName").ifBlank { o.optString("singerName") }.ifBlank { "未知歌手" }
        val album = o.optString("albumName").ifBlank {
            o.optJSONObject("albumNamePair")?.optString("name").orEmpty()
        }
        val cover = o.optJSONObject("picture")?.let {
            it.optString("bigImgURL").ifBlank { it.optString("middleImgURL") }.ifBlank { it.optString("smallImgURL") }
        }?.takeIf { it.startsWith("http") }
        val duration = durationMs(o)
        val available = o.optString("status").let { it.isBlank() || it == "1" }
        return RemoteTrack(
            remoteId = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            coverUrl = cover,
            playable = available,
            vip = false,
            unavailableReason = if (available) null else "无版权或已下架"
        )
    }

    private fun playlistFrom(o: JSONObject?): RemotePlaylist? {
        if (o == null) return null
        val id = o.optString("contentID")
            .ifBlank { o.optString("musicListID") }
            .ifBlank { o.optString("playListID") }
        val name = o.optString("contentName")
            .ifBlank { o.optString("name") }
            .ifBlank { o.optString("listName") }
            .ifBlank { o.optString("playListName") }
        if (id.isBlank() || name.isBlank()) return null
        val cover = o.optJSONObject("picture")?.let {
            it.optString("bigImgURL").ifBlank { it.optString("middleImgURL") }.ifBlank { it.optString("smallImgURL") }
        }?.takeIf { it.startsWith("http") }
        val creator = o.optString("nickName").ifBlank {
            runCatching { JSONObject(o.optString("contentExInfo")).optString("nickName") }.getOrDefault("")
        }
        val count = o.optString("totalCount").toIntOrNull()
            ?: o.optString("songCount").toIntOrNull()
            ?: o.optString("count").toIntOrNull()
            ?: o.optString("songNum").toIntOrNull()
            ?: o.optString("listSongNum").toIntOrNull()
            ?: 0
        return RemotePlaylist(
            remoteId = id,
            name = name,
            coverUrl = cover,
            trackCount = count,
            creatorName = creator
        )
    }

    private fun contentTypeOf(o: JSONObject): String =
        o.optString("contentType").ifBlank { o.optJSONObject("contentInfo")?.optString("contentType").orEmpty() }

    private fun durationMs(o: JSONObject): Long {
        val exRaw = o.optString("contentExInfo")
        if (exRaw.isBlank()) return 0
        return runCatching {
            val ex = JSONObject(exRaw)
            val infos = ex.optJSONObject("completeFileInfos") ?: return@runCatching 0L
            val keys = infos.keys()
            while (keys.hasNext()) {
                val seconds = infos.optJSONObject(keys.next())?.optString("duration")?.toLongOrNull()
                if (seconds != null && seconds > 0) return@runCatching seconds * 1000
            }
            0L
        }.getOrDefault(0L)
    }

    private fun splitArtists(raw: String): List<String> =
        raw.split('/', '、', ',', '&', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "未知歌手" }

    private fun languageOf(track: RemoteTrack): String {
        val text = track.title + " " + track.artist
        return when {
            text.any { it in '\u3040'..'\u30ff' } -> "ja"
            text.any { it in '\u1100'..'\u11ff' || it in '\uac00'..'\ud7af' } -> "ko"
            text.any { it in '\u4e00'..'\u9fff' } -> "zh"
            text.any { it.isLetter() && it.code < 128 } -> "en"
            else -> "unknown"
        }
    }

    private fun tokensOf(title: String): List<String> {
        val out = mutableListOf<String>()
        for (run in Regex("[\u4e00-\u9fff]{2,}").findAll(title)) {
            val text = run.value
            for (i in 0..text.length - 2) {
                val token = text.substring(i, i + 2)
                if (token !in STOPWORDS) out += token
            }
        }
        Regex("[A-Za-z]{3,}").findAll(title.lowercase()).forEach { out += it.value }
        return out.filter { it.length >= 2 }
    }

    private fun isAccompaniment(title: String): Boolean =
        listOf("伴奏", "纯音乐", "消音", "karaoke", "instrumental", "backing track")
            .any { title.contains(it, ignoreCase = true) }

    private val STOPWORDS = setOf(
        "我们", "你们", "他们", "一个", "一种", "没有", "什么", "怎么", "这样", "那样",
        "因为", "所以", "但是", "还是", "就是", "不是", "不要", "一直", "知道", "觉得",
        "时候", "现在", "只能", "真的", "那些", "这些", "这里", "那里", "为何", "可以",
        "自己", "让爱", "我和", "与你", "之间", "一次"
    )
}
