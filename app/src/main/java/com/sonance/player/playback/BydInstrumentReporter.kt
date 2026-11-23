package com.sonance.player.playback

import android.content.Context
import android.media.AudioManager
import android.util.Log
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.charset.Charset

/**
 * 比亚迪 DiLink 仪表盘“当前播放”信息上报。
 *
 * 两条链路并行，全部反射、任何一步失败都静默降级，绝不影响播放：
 *
 * A. 系统媒体中心扩展：`AudioManager.setPlayMediaInfo/setPlayState/setPlayProgress`。
 *    必须先 `setMediaType(8)`（LOCAL_AUDIO），否则内部 mediaMode 还是 -1，所有上报会被丢弃。
 *
 * B. 仪表盘直推：`BYDAutoInstrumentDevice.sendMusicInfo(UTF-16LE, ≤96B)`、
 *    `sendMusicState`、`sendMusicPlaybackProgress`，以及
 *    `BYDAutoAudioDevice.sendSingerName/sendAudioTotalTime/sendAudioPlayingTime`。
 *    系统侧要求调用者当前持有音频焦点；部分车型的 VHAL 还按平台签名做 ACL，
 *    非平台签名应用会被 `nativeSetInt permission deny` 拒绝 —— 这种情况我们无法绕过，
 *    但标准 MediaSession 的 title/artworkData 仍可作为兼容回退。
 *
 * 比亚迪的仪表媒体协议本身只有 歌名/歌手/专辑/时长，没有封面字段。若某些车型的
 * 厂商 API 额外提供了 `sendMusicAlbumPic` 一类 byte[] 方法，这里会动态探测并顺带发送
 * MediaSession 里已经压缩好的封面字节。
 */
object BydInstrumentReporter {

    private const val TAG = "BydInstrument"
    private const val MODE_MUSIC = 1
    private const val MEDIA_TYPE_LOCAL_AUDIO = 8
    private const val STATE_PLAY = 0
    private const val STATE_PAUSE = 1
    private const val STATE_STOP = 2
    private const val INSTR_STATE_PLAY = 1
    private const val INSTR_STATE_PAUSE = 2
    private const val INSTR_SOURCE_LOCAL = 1
    private const val TITLE_BYTE_LIMIT = 96

    private var resolved = false
    private var unavailable = false

    // A 链路
    private var mediaInfoClass: Class<*>? = null
    private var mediaInfoCtor: java.lang.reflect.Constructor<*>? = null
    private var setPlayMediaInfo: Method? = null
    private var setPlayState: Method? = null
    private var setPlayProgress: Method? = null
    private var setMediaType: Method? = null
    private var getMediaType: Method? = null

    // B 链路
    private var instrumentDevice: Any? = null
    private var audioDevice: Any? = null
    private var sendMusicInfo: Method? = null
    private var sendMusicState: Method? = null
    private var sendMusicProgress: Method? = null
    private var sendMusicSource: Method? = null
    private var sendSingerName: Method? = null
    private var sendAudioTotalTime: Method? = null
    private var sendAudioPlayingTime: Method? = null
    private data class DeviceMethod(val method: Method, val target: Any)
    private var coverMethods: List<DeviceMethod> = emptyList()

    private var focusMethod: Method? = null
    private var sourceSent = false
    private var lastSongKey: String? = null
    private var lastStateKey: String? = null
    private var pendingTitle: String? = null
    private var pendingArtist: String? = null
    private var pendingCover: ByteArray? = null
    private var infoDelivered = false

    @Synchronized
    private fun ensure(context: Context): Boolean {
        if (resolved) return !unavailable
        resolved = true
        val app = context.applicationContext

        // ---- A 链路：不影响 B，单独 try ----
        runCatching {
            mediaInfoClass = Class.forName("android.hardware.bydauto.multimedia.MediaInfo")
            mediaInfoCtor = mediaInfoClass?.getDeclaredConstructor()?.apply { isAccessible = true }
            val am = AudioManager::class.java
            setPlayMediaInfo = mediaInfoClass?.let { am.getMethod("setPlayMediaInfo", Int::class.javaPrimitiveType, it) }
            setPlayState = am.getMethod("setPlayState", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            setPlayProgress = am.getMethod("setPlayProgress", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            setMediaType = am.getMethod("setMediaType", Int::class.javaPrimitiveType)
            getMediaType = am.getMethod("getMediaType")
            focusMethod = runCatching { am.getMethod("getCurrentAudioFocusPackage") }.getOrNull()
            Log.i(TAG, "BYD media-center path available")
        }.onFailure { Log.i(TAG, "BYD media-center path unavailable: ${root(it)}") }

        // ---- B 链路：仪表盘 / 音频设备 ----
        runCatching {
            val instClass = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
            instrumentDevice = instClass.getMethod("getInstance", Context::class.java).invoke(null, app)
            sendMusicInfo = instClass.getMethod("sendMusicInfo", ByteArray::class.java)
            sendMusicState = instClass.getMethod("sendMusicState", Int::class.javaPrimitiveType)
            sendMusicProgress = instClass.getMethod("sendMusicPlaybackProgress", Int::class.javaPrimitiveType)
            sendMusicSource = instClass.getMethod("sendMusicSource", Int::class.javaPrimitiveType)

            val audioClass = Class.forName("android.hardware.bydauto.audio.BYDAutoAudioDevice")
            audioDevice = audioClass.getMethod("getInstance", Context::class.java).invoke(null, app)
            sendSingerName = audioClass.getMethod("sendSingerName", String::class.java)
            sendAudioTotalTime = audioClass.getMethod(
                "sendAudioTotalTime", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            sendAudioPlayingTime = audioClass.getMethod(
                "sendAudioPlayingTime", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            coverMethods = (
                instClass.methods.map { DeviceMethod(it, instrumentDevice!!) } +
                    audioClass.methods.map { DeviceMethod(it, audioDevice!!) }
                ).filter { dm ->
                val m = dm.method
                val n = m.name.lowercase()
                m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == ByteArray::class.java &&
                    (n.contains("album") || n.contains("cover") || n.contains("artwork")) &&
                    (n.contains("pic") || n.contains("cover") || n.contains("art"))
            }.distinctBy { it.method.name }
            Log.i(TAG, "BYD instrument path available, cover methods=${coverMethods.map { it.method.name }}")
        }.onFailure { Log.i(TAG, "BYD instrument path unavailable: ${root(it)}") }

        val hasA = setPlayMediaInfo != null
        val hasB = instrumentDevice != null && sendMusicInfo != null
        unavailable = !hasA && !hasB
        return !unavailable
    }

    private fun audioManager(context: Context): AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun ensureMediaType(context: Context) {
        val setter = setMediaType ?: return
        val getter = getMediaType ?: return
        runCatching {
            val current = getter.invoke(audioManager(context)) as? Int
            if (current != MEDIA_TYPE_LOCAL_AUDIO) {
                setter.invoke(audioManager(context), MEDIA_TYPE_LOCAL_AUDIO)
                Log.i(TAG, "mediaType -> LOCAL_AUDIO (was $current)")
            }
        }.onFailure { Log.w(TAG, "ensureMediaType failed: ${root(it)}") }
    }

    /** 当前音频焦点是否属于本应用。拿不到这个隐藏 API 时按 true 处理，让调用自行尝试。 */
    private fun focusIsOurs(context: Context): Boolean {
        val m = focusMethod ?: return true
        return runCatching {
            context.packageName == m.invoke(audioManager(context))
        }.getOrDefault(true)
    }

    private fun root(t: Throwable): Throwable {
        var c: Throwable = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return c
    }

    /**
     * 切歌 / 封面下载完成后调用。重复调用会按歌曲指纹去重。
     *
     * @param coverBytes 已压缩的 JPEG 字节（来自 MediaSession artworkData），没有就传 null。
     */
    @Synchronized
    fun reportSong(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        coverBytes: ByteArray? = null
    ) {
        if (!ensure(context)) return
        val key = "$title|$artist|$album|$durationMs|${coverBytes?.size ?: 0}"
        if (key == lastSongKey) return
        lastSongKey = key

        pendingTitle = title
        pendingArtist = artist
        pendingCover = coverBytes
        pendingDurationSec = (durationMs / 1000L).toInt().coerceAtLeast(0)
        infoDelivered = false

        ensureMediaType(context)
        reportMediaCenterSong(context, title, artist, album, durationMs, coverBytes)
        sendDirectInfoIfFocused(context)
    }

    private fun reportMediaCenterSong(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        coverBytes: ByteArray?
    ) {
        val cls = mediaInfoClass ?: return
        val ctor = mediaInfoCtor ?: return
        val method = setPlayMediaInfo ?: return
        runCatching {
            val info = ctor.newInstance()
            setField(info, "fileName", title)
            setField(info, "artistName", artist)
            setField(info, "albumName", album)
            setField(info, "duration", durationMs.toInt())
            // 不同车型的 MediaInfo 可能扩展了封面字段。只在字段确实存在时填，绝不猜。
            if (coverBytes != null) {
                for (f in cls.fields) {
                    val n = f.name.lowercase()
                    if (n.contains("cover") || n.contains("albumpic") || n.contains("artwork")) {
                        runCatching {
                            when {
                                f.type == ByteArray::class.java -> f.set(info, coverBytes)
                                f.type == String::class.java -> f.set(info, coverPath(context, coverBytes))
                            }
                        }
                    }
                }
            }
            method.invoke(audioManager(context), MODE_MUSIC, info)
            Log.i(TAG, "media-center info sent: $title / $artist")
        }.onFailure { Log.w(TAG, "setPlayMediaInfo failed: ${root(it)}") }
    }

    private fun setField(obj: Any, name: String, value: Any) {
        runCatching {
            val f: Field = obj.javaClass.getField(name)
            f.set(obj, value)
        }
    }

    private fun coverPath(context: Context, bytes: ByteArray): String? = runCatching {
        val f = File(context.cacheDir, "byd_instrument_cover.jpg")
        if (!f.isFile || !f.readBytes().contentEquals(bytes)) f.writeBytes(bytes)
        f.absolutePath
    }.getOrNull()

    private fun sendDirectInfoIfFocused(context: Context) {
        if (infoDelivered) return
        val title = pendingTitle ?: return
        if (!focusIsOurs(context)) return
        runCatching {
            if (!sourceSent && sendMusicSource != null) {
                sendMusicSource?.invoke(instrumentDevice, INSTR_SOURCE_LOCAL)
                sourceSent = true
            }
            sendMusicInfo?.invoke(instrumentDevice, titleBytes(title))
            pendingArtist?.takeIf { it.isNotBlank() }?.let { sendSingerName?.invoke(audioDevice, it) }
            val totalSec = (pendingDurationSec ?: 0).coerceAtLeast(0)
            sendAudioTotalTime?.invoke(audioDevice, totalSec / 3600, totalSec % 3600 / 60, totalSec % 60)
            pendingCover?.let { bytes ->
                for (dm in coverMethods) {
                    runCatching { dm.method.invoke(dm.target, bytes) }
                        .onFailure { Log.d(TAG, "${dm.method.name} cover send skipped: ${root(it)}") }
                }
            }
            infoDelivered = true
            Log.i(TAG, "instrument info sent: $title / ${pendingArtist.orEmpty()}")
        }.onFailure { Log.w(TAG, "instrument direct report failed: ${root(it)}") }
    }

    private var pendingDurationSec: Int? = null

    @Synchronized
    fun reportState(context: Context, playing: Boolean) {
        if (!ensure(context)) return
        val key = if (playing) "play" else "pause"
        if (key == lastStateKey) return
        lastStateKey = key
        ensureMediaType(context)
        runCatching {
            setPlayState?.invoke(audioManager(context), MODE_MUSIC, if (playing) STATE_PLAY else STATE_PAUSE)
        }.onFailure { Log.w(TAG, "setPlayState failed: ${root(it)}") }
        if (focusIsOurs(context)) {
            runCatching {
                sendMusicState?.invoke(instrumentDevice, if (playing) INSTR_STATE_PLAY else INSTR_STATE_PAUSE)
            }.onFailure { Log.w(TAG, "sendMusicState failed: ${root(it)}") }
        }
    }

    /** 播放中约每秒调用一次；B 链路未送达时顺带补送歌曲信息。 */
    @Synchronized
    fun reportProgress(context: Context, positionMs: Long, durationMs: Long) {
        if (!ensure(context)) return
        val positionSec = (positionMs / 1000L).toInt().coerceAtLeast(0)
        runCatching {
            setPlayProgress?.invoke(audioManager(context), MODE_MUSIC, positionSec)
        }.onFailure { Log.d(TAG, "setPlayProgress skipped: ${root(it)}") }

        sendDirectInfoIfFocused(context)
        if (!focusIsOurs(context)) return
        runCatching {
            val totalSec = (durationMs / 1000L).toInt().coerceAtLeast(0)
            val percent = if (totalSec > 0) (positionSec * 100 / totalSec).coerceIn(0, 100) else 0
            sendMusicProgress?.invoke(instrumentDevice, percent)
            sendAudioPlayingTime?.invoke(audioDevice, positionSec / 3600, positionSec % 3600 / 60, positionSec % 60)
        }.onFailure { Log.d(TAG, "instrument progress skipped: ${root(it)}") }
    }

    @Synchronized
    fun reportStop(context: Context) {
        if (!ensure(context)) return
        runCatching { setPlayState?.invoke(audioManager(context), MODE_MUSIC, STATE_STOP) }
        runCatching { sendMusicState?.invoke(instrumentDevice, INSTR_STATE_PAUSE) }
    }

    private fun titleBytes(title: String): ByteArray {
        val bytes = title.toByteArray(Charset.forName("UTF-16LE"))
        val evenLimit = TITLE_BYTE_LIMIT - (TITLE_BYTE_LIMIT % 4)
        return if (bytes.size > evenLimit) bytes.copyOf(evenLimit) else bytes
    }
}
