package com.sonance.player.data.huawei

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Huawei Music account bridge.
 *
 * The official OAuth page is opened in a WebView by the caller; this object only builds the
 * PKCE authorize URL, validates the callback and performs the two token exchanges. Tokens are
 * kept in a private SharedPreferences file so a process restart does not force a new scan.
 */
object HuaweiAuth {

    private const val TAG = "SonanceHuawei"
    private const val PREFS = "sonance_huawei_account"
    private const val KEY_ACCOUNT = "account"

    const val AUTHORIZE = "https://oauth-login.platform.hicloud.com/oauth2/v3/authorize"
    const val TOKEN = "https://oauth-login.cloud.huawei.com/oauth2/v3/token"
    const val MUSIC = "https://api-drcn.music.dbankcloud.cn"
    const val CLIENT_ID = "101444301"
    const val REDIRECT_URI = "hms://redirect_uri"
    private const val EXPIRY_MS = 10 * 60_000L

    data class Account(
        val accessToken: String,
        val token: String,
        val snsUserId: String,
        val expireTime: String,
        val nickname: String,
        val savedAtMs: Long
    )

    private data class Pending(val verifier: String, val state: String, val createdAt: Long)

    @Volatile
    var account: Account? = null
        private set

    private var pending: Pending? = null
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACCOUNT, null) ?: return
        account = runCatching {
            val o = JSONObject(raw)
            Account(
                accessToken = o.optString("accessToken"),
                token = o.optString("token"),
                snsUserId = o.optString("snsUserID"),
                expireTime = o.optString("expireTime"),
                nickname = o.optString("nickname"),
                savedAtMs = o.optLong("savedAtMs")
            ).takeIf { it.token.isNotBlank() }
        }.getOrNull()
    }

    fun begin(): String {
        val verifier = randomUrlSafe(48)
        val state = randomUrlSafe(32)
        pending = Pending(verifier, state, System.currentTimeMillis())
        val q = linkedMapOf(
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "response_type" to "code",
            "access_type" to "offline",
            "scope" to "openid",
            "state" to state,
            "code_challenge" to challenge(verifier),
            "code_challenge_method" to "S256",
            "countryCode" to "cn",
            "ui_locales" to "zh-cn"
        )
        return AUTHORIZE + "?" + q.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
    }

    /** Exchanges the `hms://redirect_uri?code=...` callback for a music session. Blocking IO. */
    fun complete(context: Context, callback: String): Account {
        val attempt = pending ?: throw IllegalStateException("登录尝试已失效，请重新打开登录页")
        if (System.currentTimeMillis() - attempt.createdAt > EXPIRY_MS) {
            pending = null
            throw IllegalStateException("登录超时，请重新扫码")
        }
        // 注意：java.net.URL 只认已注册协议，遇到 hms:// 会抛 MalformedURLException，
        // 这里必须用 Android 的 Uri，它把自定义 scheme 当普通字符串解析。
        val uri = Uri.parse(callback)
        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
            ?: throw IllegalException(
                uri.getQueryParameter("error_description")
                    ?: uri.getQueryParameter("error")
                    ?: "授权回调缺少授权码"
            )
        val state = uri.getQueryParameter("state").orEmpty()
        if (state != attempt.state) throw IllegalException("授权状态不匹配，请重新登录")
        pending = null

        val tokenJson = postForm(
            TOKEN,
            linkedMapOf(
                "grant_type" to "authorization_code",
                "client_id" to CLIENT_ID,
                "redirect_uri" to REDIRECT_URI,
                "code" to code,
                "code_verifier" to attempt.verifier
            ),
            null
        )
        val accessToken = tokenJson.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IllegalException("华为账号未返回访问令牌")

        val session = HuaweiHttp.postJson(
            "/music-odpapp-service/v5/service/userinfo/loginex",
            JSONObject().put("accessToken", accessToken)
        )
        val musicToken = session.optString("token").takeIf { it.isNotBlank() }
            ?: throw IllegalException("华为音乐未返回会话令牌")

        val account = Account(
            accessToken = accessToken,
            token = musicToken,
            snsUserId = session.optString("snsUserID"),
            expireTime = session.optString("expireTime"),
            nickname = session.optString("nickName")
                .ifBlank { session.optString("nickname") }
                .ifBlank { "华为音乐" },
            savedAtMs = System.currentTimeMillis()
        )
        save(context, account)
        return account
    }

    fun save(context: Context, acc: Account) {
        account = acc
        val json = JSONObject()
            .put("accessToken", acc.accessToken)
            .put("token", acc.token)
            .put("snsUserID", acc.snsUserId)
            .put("expireTime", acc.expireTime)
            .put("nickname", acc.nickname)
            .put("savedAtMs", acc.savedAtMs)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACCOUNT, json.toString()).apply()
    }

    fun logout(context: Context) {
        account = null
        pending = null
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_ACCOUNT).apply()
    }

    fun token(): String? = account?.token

    // ---- PKCE helpers ----

    private fun randomUrlSafe(bytes: Int): String {
        val data = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun postForm(url: String, form: Map<String, String>, extraHeaders: Map<String, String>?): JSONObject {
        val body = form.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
            .toByteArray(Charsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            extraHeaders?.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            connection.outputStream.use { it.write(body) }
            val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private class IllegalException(message: String) : Exception(message)
}
