package com.loltube.app.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.loltube.app.ui.components.ImmersiveLoading
import com.loltube.app.util.AppLog

/**
 * Last-resort playback: run the provider's own player inside a visible WebView.
 * Popups blocked, cross-origin navigation blocked, video stretched to fill.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewPlayerScreen(url: String, onBack: () -> Unit) {
    val pageHost = remember(url) { android.net.Uri.parse(url).host ?: "" }
    BackHandler(onBack = onBack)

    // modern immersive loading until the page has rendered its player
    var pageReady by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val host = request.url.host ?: return false
                            val block = host != pageHost
                            if (block) AppLog.d("WebViewPlayer", "blocked navigation to $host")
                            return block
                        }

                        override fun onPageStarted(view: WebView, u: String, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, u, favicon)
                            pageReady = false
                        }

                        override fun onPageFinished(view: WebView, u: String) {
                            super.onPageFinished(view, u)
                            view.evaluateJavascript(
                                """
                                (function(){
                                  try {
                                    var s = document.createElement('style');
                                    s.textContent = 'html,body{margin:0!important;height:100%!important;overflow:hidden!important;background:#000}' +
                                      'video{object-fit:fill!important;width:100vw!important;height:100vh!important}';
                                    document.head.appendChild(s);
                                    document.querySelectorAll('video').forEach(function(v){
                                      v.setAttribute('style','object-fit:fill!important;width:100vw!important;height:100vh!important');
                                      var p = v.play(); if (p && p.catch) p.catch(function(){});
                                    });
                                  } catch(e) {}
                                })();
                                """.trimIndent(),
                                null,
                            )
                            // keep the immersive loader up a short beat past
                            // onPageFinished - the player still mounts its video
                            // right after the DOM is ready
                            view.postDelayed({ pageReady = true }, 900)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?,
                        ): Boolean = false

                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            AppLog.d("WebViewPlayer", "js: ${m.message().take(160)}")
                            return true
                        }
                    }
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (!pageReady) {
            ImmersiveLoading(
                posterUrl = null,
                title = null,
                status = "Loading the site's own player…",
                modifier = Modifier.fillMaxSize(),
            )
        }

        // small always-visible back button so the user can never get stuck
        Surface(
            color = Color(0x88000000),
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
        }
    }
}
