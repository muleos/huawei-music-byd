package com.sonance.player.data.huawei

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Thin JSON client for api-drcn.music.dbankcloud.cn, including the observed Digest header. */
object HuaweiHttp {

    fun postJson(
        path: String,
        body: JSONObject,
        authenticated: Boolean = false,
        timeoutMs: Int = 20_000
    ): JSONObject {
        val token = if (authenticated) {
            HuaweiAuth.token() ?: throw IllegalStateException("请先登录华为音乐账号")
        } else null
        val connection = (URL(HuaweiAuth.MUSIC + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", digest(token))
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val json = JSONObject(text)
            val result = json.optJSONObject("result")
            val code = result?.optString("resultCode").orEmpty()
            if (!connection.responseCode.toString().startsWith("2") || (code.isNotEmpty() && code != "000000")) {
                throw IllegalStateException(result?.optString("resultMessage").orEmpty().ifBlank { "华为音乐请求失败（${connection.responseCode}）" })
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    fun getJson(path: String, authenticated: Boolean = false, timeoutMs: Int = 20_000): JSONObject {
        val token = if (authenticated) {
            HuaweiAuth.token() ?: throw IllegalStateException("请先登录华为音乐账号")
        } else null
        val connection = (URL(HuaweiAuth.MUSIC + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", digest(token))
        }
        return try {
            val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val json = JSONObject(text)
            val code = json.optJSONObject("result")?.optString("resultCode").orEmpty()
            if (!connection.responseCode.toString().startsWith("2") || (code.isNotEmpty() && code != "000000")) {
                throw IllegalStateException(json.optJSONObject("result")?.optString("resultMessage").orEmpty().ifBlank { "华为音乐请求失败" })
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun digest(token: String?): String {
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val nonce = Base64.encodeToString(
            ByteArray(24).also { SecureRandom().nextBytes(it) },
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        if (!token.isNullOrBlank() && token.any { it == '\n' || it == '\r' || it == ',' }) {
            throw IllegalArgumentException("音乐会话令牌格式无效")
        }
        return "Digest username=app-music-baseline, nonce=$nonce, created=$created" +
            if (token.isNullOrBlank()) "" else ", token=$token"
    }
}
