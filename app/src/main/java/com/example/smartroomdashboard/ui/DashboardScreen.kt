package com.example.smartroomdashboard.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.smartroomdashboard.domain.AppSettings
import com.example.smartroomdashboard.domain.normalizedBaseUrl
import org.json.JSONObject

private class HomeAssistantAuthBridge(
    private val token: String,
) {
    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(view: WebView) {
        webView = view
    }

    @JavascriptInterface
    fun getExternalAuth(message: String) {
        handle(message)
    }

    @JavascriptInterface
    fun postMessage(message: String) {
        handle(message)
    }

    @JavascriptInterface
    fun revokeExternalAuth(message: String) {
        val callback = callbackFor(message, "externalAuthRevokeToken") ?: return
        evaluate("$callback(true);")
    }

    private fun handle(message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return
        if (json.optString("type", "getExternalAuth") != "getExternalAuth") return
        val payload = json.optJSONObject("payload") ?: json
        val callback = payload.optString("callback")
        if (callback != "externalAuthSetToken") return
        val tokenJson = JSONObject.quote(token)
        evaluate("$callback(true, {access_token: $tokenJson, expires_in: 31536000});")
    }

    private fun callbackFor(message: String, expected: String): String? {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return null
        val payload = json.optJSONObject("payload") ?: json
        return payload.optString("callback").takeIf { it == expected }
    }

    private fun evaluate(script: String) {
        mainHandler.post { webView?.evaluateJavascript(script, null) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    settings: AppSettings,
    tokenProvider: suspend () -> String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var token by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        token = tokenProvider().ifBlank { null }
    }

    val dashboardUrl = remember(settings.homeAssistantUrl, settings.dashboardPath) {
        settings.homeAssistantUrl.normalizedBaseUrl() +
            settings.dashboardPath.trim().trim('/').ifBlank { "lovelace/0" } +
            "?external_auth=1"
    }

    fun loadDashboard() {
        error = null
        loaded = false
        webView?.loadUrl(dashboardUrl)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home Assistant") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = ::loadDashboard) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Reload dashboard")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (token == null) {
                Text(
                    "Save a Home Assistant URL and token in Settings before opening the dashboard.",
                    modifier = Modifier.padding(16.dp),
                )
            } else if (error != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(error.orEmpty())
                    Button(onClick = ::loadDashboard) { Text("Retry") }
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        WebView(context).also { view ->
                            val bridge = HomeAssistantAuthBridge(token.orEmpty())
                            view.settings.javaScriptEnabled = true
                            view.settings.domStorageEnabled = true
                            view.settings.setSupportZoom(true)
                            view.settings.builtInZoomControls = true
                            view.settings.displayZoomControls = false
                            view.overScrollMode = WebView.OVER_SCROLL_NEVER
                            view.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    loaded = true
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    receivedError: WebResourceError,
                                ) {
                                    if (request.isForMainFrame) {
                                        error = "Dashboard failed to load: ${receivedError.description}"
                                    }
                                }
                            }
                            view.addJavascriptInterface(bridge, "externalApp")
                            bridge.attach(view)
                            view.loadUrl(dashboardUrl)
                            webView = view
                        }
                    },
                    update = {},
                )
            }
        }
    }
}
