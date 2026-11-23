package com.sonance.player.ui.sheets

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.huawei.HuaweiAuth
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.solidSurface
import kotlinx.coroutines.launch

/**
 * Official Huawei account page inside a bottom sheet. The OAuth redirect is intercepted before
 * the WebView navigates away; the token exchange then runs in the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuaweiLoginSheet(vm: SonanceViewModel, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val authorizeUrl = remember { HuaweiAuth.begin() }
    var status by remember { mutableStateOf("请在官方页面扫码或输入华为账号") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun handle(callback: String) {
        if (status.startsWith("正在完成")) return
        status = "正在完成登录…"
        scope.launch {
            val error = vm.completeHuaweiLogin(callback)
            if (error == null) onDismiss() else status = error
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = skin.surface.solidSurface(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 20.dp)) {
            Text(
                "登录华为音乐",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = skin.text
            )
            Text(
                "账号与扫码都在华为官方页面完成，登录后可搜索并播放会员曲库。",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(540.dp),
                factory = { context ->
                    WebView(context).apply {
                        val view = this
                        webView = this
                        setBackgroundColor(Color.WHITE)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            textZoom = 100
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                forceDark = WebSettings.FORCE_DARK_OFF
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                isAlgorithmicDarkeningAllowed = false
                            }
                            allowFileAccess = false
                            allowContentAccess = false
                            setSupportMultipleWindows(false)
                            userAgentString = userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
                        }
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(view, true)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                if (url.startsWith("hms://redirect_uri", ignoreCase = true)) {
                                    handle(url)
                                    return true
                                }
                                return !allowed(url)
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                if (url != null && url.startsWith("hms://redirect_uri", ignoreCase = true)) handle(url)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // 华为登录页在深色 WebView 里会把部分按钮渲染成透明底，导致按钮“消失”。
                                // 强制浅色配色，并把计算后仍为透明的按钮补成白底描边，保证扫码/登录按钮可见。
                                view?.evaluateJavascript(
                                    """
                                    (function(){
                                      document.documentElement.style.colorScheme='light';
                                      var st=document.createElement('style');
                                      st.textContent='html,body{background:#ffffff !important;color-scheme:light !important;}';
                                      (document.head||document.documentElement).appendChild(st);
                                      function fix(){
                                        document.querySelectorAll('button,input[type=submit],[role=button],[class*=btn],[class*=button]').forEach(function(e){
                                          var cs=getComputedStyle(e);
                                          if(cs.backgroundColor==='rgba(0, 0, 0, 0)'||cs.backgroundColor==='transparent'){
                                            e.style.backgroundColor='#ffffff';
                                            e.style.color='#111111';
                                            e.style.border='1px solid #c9ced6';
                                            e.style.boxShadow='none';
                                            e.style.opacity='1';
                                          }
                                        });
                                      }
                                      fix(); setTimeout(fix, 600); setTimeout(fix, 1800);
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                                if (status == "正在加载华为账号页面…") status = "请在官方页面扫码或输入华为账号"
                            }
                        }
                        status = "正在加载华为账号页面…"
                        loadUrl(authorizeUrl)
                    }
                }
            )
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
}

private fun allowed(url: String): Boolean {
    if (url.startsWith("about:") || url.startsWith("https://appassets.androidplatform.net")) return true
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    if (parsed.scheme != "https") return false
    val host = parsed.host?.lowercase() ?: return false
    return host.endsWith(".huawei.com") || host.endsWith(".hicloud.com") || host == "huawei.com"
}
