package com.sonance.player.data.huawei

import com.sonance.player.data.RemoteTrack
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * 「猜你喜欢」口味模型。
 *
 * 这是桌面版 `recommend.mjs`（v5, precision-oriented）的 Kotlin 移植：收藏、歌单、
 * 历史按不同置信度喂给画像，候选按 0-100 的契合度排序，低于学到的接受线直接丢弃，
 * 再用逐级放宽的歌手/专辑上限保证多样性。
 *
 * 和桌面版的唯一差别是身份键：华为 Android 接口只返回歌手名 / 专辑名，没有稳定的
 * artistID / albumID，所以这里用规范化后的名字当键（去空白、转小写）。
 */
internal object HuaweiRecommender {

    data class Seed(val track: RemoteTrack, val weight: Double)

    data class Profile(
        val artistWeights: Map<String, Double>,
        val albumWeights: Map<String, Double>,
        val languageWeights: Map<String, Double>,
        val versionWeights: Map<String, Double>,
        val versionCounts: Map<String, Int>,
        val durationMeanMs: Double,
        val durationSpreadMs: Double,
        val total: Double,
    )

    data class ScoreProfile(
        val target: Double,
        val floor: Double,
        val tolerance: Double,
        val count: Double,
    )

    class Model(
        val profile: Profile,
        val playlistHits: Map<String, Int>,
        val themeTokens: List<Pair<String, Double>>,
    ) {
        val artistAffinity: Map<String, Double> = normalise(profile.artistWeights)
        val albumAffinity: Map<String, Double> = normalise(profile.albumWeights)
        private val languageShares: Map<String, Double> = profile.languageWeights
        private val versionShares: Map<String, Double> = profile.versionWeights
        private val themeCounts: Map<String, Double> = themeTokens.toMap()
        private val maxPlaylistHits: Int = playlistHits.values.maxOrNull() ?: 0

        val accompanimentShare: Double =
            if (profile.total > 0) (versionShares["instrumental"] ?: 0.0) / profile.total else 0.0
        val accompanimentCount: Int = profile.versionCounts["instrumental"] ?: 0
        val accompanimentAllowed: Boolean =
            accompanimentShare >= ACCOMPANIMENT_TOLERANCE_SHARE && accompanimentCount >= ACCOMPANIMENT_MIN_COUNT

        private fun normalise(map: Map<String, Double>): Map<String, Double> {
            val top = map.values.maxOrNull() ?: 0.0
            if (top <= 0.0) return emptyMap()
            return map.mapValues { (_, value) -> clamp01(value / top) }
        }

        fun artistAffinity(key: String): Double = artistAffinity[key] ?: 0.0
        fun albumAffinity(key: String): Double = albumAffinity[key] ?: 0.0
        fun languageFit(language: String): Double {
            if (language == "unknown") return 0.25
            val share = languageShares[language] ?: 0.0
            return if (profile.total > 0) share / profile.total else 0.0
        }

        fun versionFit(tags: List<String>): Double {
            if (tags.isEmpty()) return 1.0
            if ("instrumental" in tags) return if (accompanimentAllowed) 0.9 else 0.1
            var share = 0.0
            for (tag in tags) {
                val count = versionShares[tag] ?: 0.0
                if (profile.total > 0) share = max(share, count / profile.total)
            }
            return if (share >= VERSION_HABIT_SHARE) 0.9 else 0.25
        }

        fun themeFit(title: String): Double {
            if (themeCounts.isEmpty()) return 0.5
            val tokens = tokenizeTitle(title).distinct()
            if (tokens.isEmpty()) return 0.25
            val hits = tokens.count { themeCounts.containsKey(it) }
            return clamp01(0.2 + 0.8 * hits / min(3, tokens.size).toDouble())
        }

        fun buzz(id: String): Double {
            if (maxPlaylistHits <= 0) return 0.0
            return clamp01((playlistHits[id] ?: 0).toDouble() / maxPlaylistHits)
        }

        fun durationFit(durationMs: Long): Double {
            val stats = profile
            if (stats.durationMeanMs <= 0.0 || durationMs <= 0L) return 0.45
            val spread = max(stats.durationSpreadMs, 30_000.0)
            val distance = abs(durationMs - stats.durationMeanMs)
            return clamp01(1.0 - distance / (2.0 * spread))
        }
    }

    private val SCORE_WEIGHTS = mapOf(
        "artist" to 0.34,
        "album" to 0.12,
        "language" to 0.16,
        "theme" to 0.14,
        "buzz" to 0.12,
        "duration" to 0.08,
        "version" to 0.04,
    )

    private const val VERSION_HABIT_SHARE = 0.25
    private const val ACCOMPANIMENT_TOLERANCE_SHARE = 0.34
    private const val ACCOMPANIMENT_MIN_COUNT = 3
    private val DIVERSITY_CAPS = listOf(1, 2, 3, Int.MAX_VALUE)

    private val KANA = Regex("[\\u3040-\\u30ff\\u31f0-\\u31ff]")
    private val HANGUL = Regex("[\\u1100-\\u11ff\\u3130-\\u318f\\uac00-\\ud7af]")
    private val CJK = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]")
    private val LATIN = Regex("[A-Za-z]{2}")
    private val CJK_RUN = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]{2,}")
    private val LATIN_WORD = Regex("[a-z]{3,}")

    private val VERSION_PATTERNS: List<Pair<String, Regex>> = listOf(
        "live" to Regex("\\blive\\b|演唱会|现场", RegexOption.IGNORE_CASE),
        "remix" to Regex("remix|re-mix|\\bdj\\b|混音", RegexOption.IGNORE_CASE),
        "acoustic" to Regex("acoustic|unplugged|不插电", RegexOption.IGNORE_CASE),
        "instrumental" to Regex(
            "instrumental|\\binst\\b\\.?|karaoke|off\\s*vocal|backing\\s*track|伴奏|纯音乐|纯伴奏|消音|无人声|无和声|" +
                "(?:钢琴|吉他|小提琴|古筝|二胡|八音盒|音乐盒|口琴|手风琴|萨克斯)\\s*(?:版|曲|演奏)|" +
                "(?:piano|guitar|violin|music\\s*box)\\s*(?:version|ver\\b|cover|instrumental)",
            RegexOption.IGNORE_CASE
        ),
        "cover" to Regex("cover|翻唱", RegexOption.IGNORE_CASE),
        "demo" to Regex("\\bdemo\\b|小样", RegexOption.IGNORE_CASE),
    )

    private val THEME_STOPWORDS = setOf(
        "我们", "你们", "他们", "一个", "一种", "没有", "什么", "怎么", "这样", "那样", "因为", "所以",
        "但是", "还是", "就是", "不是", "不要", "一直", "知道", "觉得", "时候", "现在", "只能", "真的",
        "那些", "这些", "这里", "那里", "为何", "可以", "自己", "让爱", "我和", "与你", "之间", "一次",
    )
    private val THEME_VERSION_WORDS = setOf(
        "live", "remix", "acoustic", "cover", "demo", "instrumental", "karaoke",
    )

    private val VERSION_QUERY = mapOf(
        "live" to "Live", "remix" to "Remix", "acoustic" to "不插电",
        "cover" to "翻唱", "demo" to "Demo",
    )

    // ------------------------------------------------------------------ text

    fun splitArtists(raw: String): List<String> =
        raw.split('/', '、', ',', '&', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "未知歌手" }

    fun artistKey(raw: String): String = raw.trim().lowercase()
    fun albumKey(raw: String): String = raw.trim().lowercase()

    fun detectLanguage(track: RemoteTrack): String {
        val text = track.title + " " + track.artist
        return when {
            KANA.containsMatchIn(text) -> "ja"
            HANGUL.containsMatchIn(text) -> "ko"
            CJK.containsMatchIn(text) -> "zh"
            LATIN.containsMatchIn(text) -> "en"
            else -> "unknown"
        }
    }

    fun versionTags(title: String): List<String> =
        VERSION_PATTERNS.filter { (_, pattern) -> pattern.containsMatchIn(title) }.map { it.first }

    /** 伴奏 / 纯音乐不是一首真正的歌；专辑名也可能是唯一的伴奏标记。 */
    fun isAccompaniment(track: RemoteTrack): Boolean =
        "instrumental" in versionTags(track.title) || "instrumental" in versionTags(track.album)

    fun tokenizeTitle(title: String): List<String> {
        val out = mutableListOf<String>()
        for (run in CJK_RUN.findAll(title)) {
            val text = run.value
            for (i in 0..text.length - 2) {
                val token = text.substring(i, i + 2)
                if (token !in THEME_STOPWORDS) out += token
            }
        }
        for (word in LATIN_WORD.findAll(title.lowercase())) {
            if (word.value !in THEME_VERSION_WORDS) out += word.value
        }
        return out
    }

    // ------------------------------------------------------------------ profile

    fun buildProfile(seeds: List<Seed>): Profile {
        val artists = linkedMapOf<String, Double>()
        val albums = linkedMapOf<String, Double>()
        val languages = linkedMapOf<String, Double>()
        val versions = linkedMapOf<String, Double>()
        val versionCounts = linkedMapOf<String, Int>()
        var total = 0.0
        var durationWeight = 0.0
        var durationSum = 0.0
        var durationSquares = 0.0

        for (seed in seeds) {
            val track = seed.track
            val weight = seed.weight
            if (weight <= 0.0) continue
            total += weight
            val accompaniment = isAccompaniment(track)

            for (artist in splitArtists(track.artist)) {
                val key = artistKey(artist)
                artists[key] = (artists[key] ?: 0.0) + weight
            }
            if (track.album.isNotBlank() && !accompaniment) {
                val key = albumKey(track.album)
                albums[key] = (albums[key] ?: 0.0) + weight
            }

            val language = detectLanguage(track)
            languages[language] = (languages[language] ?: 0.0) + weight

            val tags = versionTags(track.title).toMutableList()
            if (accompaniment && "instrumental" !in tags) tags += "instrumental"
            for (tag in tags.distinct()) {
                versions[tag] = (versions[tag] ?: 0.0) + weight
                versionCounts[tag] = (versionCounts[tag] ?: 0) + 1
            }

            if (track.durationMs > 0) {
                durationWeight += weight
                durationSum += track.durationMs * weight
                durationSquares += track.durationMs.toDouble() * track.durationMs * weight
            }
        }

        var mean = 0.0
        var spread = 30_000.0
        if (durationWeight > 0) {
            mean = durationSum / durationWeight
            val variance = max(0.0, durationSquares / durationWeight - mean * mean)
            spread = max(sqrt(variance), 30_000.0)
        }
        return Profile(
            artistWeights = artists.entries.sortedByDescending { it.value }.associate { it.toPair() },
            albumWeights = albums.entries.sortedByDescending { it.value }.associate { it.toPair() },
            languageWeights = languages,
            versionWeights = versions,
            versionCounts = versionCounts,
            durationMeanMs = mean,
            durationSpreadMs = spread,
            total = total,
        )
    }

    fun buildThemeTokens(seeds: List<Seed>, minCount: Double = 2.0, limit: Int = 12): List<Pair<String, Double>> {
        val counts = linkedMapOf<String, Double>()
        for (seed in seeds) {
            if (isAccompaniment(seed.track)) continue
            for (token in tokenizeTitle(seed.track.title)) {
                counts[token] = (counts[token] ?: 0.0) + seed.weight
            }
        }
        return counts.entries
            .filter { it.value >= minCount }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.toPair() }
    }

    // ------------------------------------------------------------------ score

    fun score(track: RemoteTrack, model: Model): Double {
        val artistValue = splitArtists(track.artist)
            .maxOfOrNull { model.artistAffinity(artistKey(it)) } ?: 0.0
        val albumValue = if (track.album.isNotBlank()) model.albumAffinity(albumKey(track.album)) else 0.0
        val languageValue = model.languageFit(detectLanguage(track))
        val versionValue = model.versionFit(versionTags(track.title))
        val durationValue = model.durationFit(track.durationMs)
        val themeValue = model.themeFit(track.title)
        val buzzValue = model.buzz(track.remoteId)

        var raw = 0.0
        raw += SCORE_WEIGHTS.getValue("artist") * artistValue
        raw += SCORE_WEIGHTS.getValue("album") * albumValue
        raw += SCORE_WEIGHTS.getValue("language") * languageValue
        raw += SCORE_WEIGHTS.getValue("theme") * themeValue
        raw += SCORE_WEIGHTS.getValue("buzz") * buzzValue
        raw += SCORE_WEIGHTS.getValue("duration") * durationValue
        raw += SCORE_WEIGHTS.getValue("version") * versionValue
        return round(raw * 1000.0) / 10.0
    }

    fun buildScoreProfile(seeds: List<Seed>, model: Model): ScoreProfile {
        if (seeds.isEmpty()) return ScoreProfile(60.0, 0.0, 18.0, 0.0)
        var weightTotal = 0.0
        var sum = 0.0
        var squares = 0.0
        var minScore = Double.MAX_VALUE
        for (seed in seeds) {
            val value = score(seed.track, model)
            weightTotal += seed.weight
            sum += value * seed.weight
            squares += value * value * seed.weight
            minScore = min(minScore, value)
        }
        if (weightTotal <= 0.0) return ScoreProfile(60.0, 0.0, 18.0, 0.0)
        val target = sum / weightTotal
        val variance = max(0.0, squares / weightTotal - target * target)
        val tolerance = max(18.0, sqrt(variance) * 1.2)
        val floor = clamp01((minScore - tolerance * 0.35) / 100.0) * 100.0
        return ScoreProfile(
            target = target,
            floor = min(floor, target),
            tolerance = tolerance,
            count = weightTotal,
        )
    }

    /**
     * 精准排序：高分在前，低于接受线的丢弃；第一轮每个歌手/专辑最多 1 首，
     * 不够再逐级放宽到 2、3、不限。伴奏在打分前就被剔除。
     */
    fun rank(
        candidates: List<RemoteTrack>,
        model: Model,
        scoreProfile: ScoreProfile,
        excludeIds: Set<String>,
        limit: Int = 120,
        floorScale: Double = 1.0,
        allowAccompaniment: Boolean = model.accompanimentAllowed,
    ): List<RemoteTrack> {
        val floor = if (scoreProfile.count > 0) scoreProfile.floor * floorScale else 0.0
        val seen = excludeIds.toMutableSet()
        data class Item(
            val track: RemoteTrack,
            val score: Double,
            val artists: List<String>,
            val album: String?,
            var taken: Boolean = false,
        )

        val scored = mutableListOf<Item>()
        for (track in candidates) {
            if (track.remoteId.isBlank() || track.remoteId in seen || !track.playable) continue
            seen += track.remoteId
            if (!allowAccompaniment && isAccompaniment(track)) continue
            val value = score(track, model)
            if (scoreProfile.count > 0 && value < floor) continue
            scored += Item(
                track = track,
                score = value,
                artists = splitArtists(track.artist).map(::artistKey).distinct(),
                album = track.album.takeIf { it.isNotBlank() }?.let(::albumKey),
            )
        }
        scored.sortWith(compareByDescending<Item> { it.score }.thenBy { it.track.remoteId })

        val picked = mutableListOf<RemoteTrack>()
        val perArtist = mutableMapOf<String, Int>()
        val perAlbum = mutableMapOf<String, Int>()
        for (cap in DIVERSITY_CAPS) {
            for (item in scored) {
                if (picked.size >= limit) break
                if (item.taken) continue
                if (item.artists.any { (perArtist[it] ?: 0) >= cap }) continue
                val album = item.album
                if (album != null && (perAlbum[album] ?: 0) >= cap) continue
                item.taken = true
                picked += item.track
                for (artist in item.artists) perArtist[artist] = (perArtist[artist] ?: 0) + 1
                if (album != null) perAlbum[album] = (perAlbum[album] ?: 0) + 1
            }
            if (picked.size >= limit) break
        }
        return picked
    }

    /** 桌面版同款搜索关键词：真正形成习惯的版本标记 + 最强的主题词，永不搜「伴奏」。 */
    fun searchKeywords(profile: Profile, themes: List<Pair<String, Double>>, limit: Int = 3): List<String> {
        val out = mutableListOf<String>()
        for ((tag, count) in profile.versionCounts) {
            if (tag == "instrumental" || count < 2) continue
            VERSION_QUERY[tag]?.let { out += it }
        }
        for ((token, _) in themes.take(3)) {
            if (token.length >= 2) out += token
        }
        return out.distinct().take(limit)
    }

    private fun clamp01(value: Double): Double = when {
        value < 0.0 -> 0.0
        value > 1.0 -> 1.0
        else -> value
    }
}
