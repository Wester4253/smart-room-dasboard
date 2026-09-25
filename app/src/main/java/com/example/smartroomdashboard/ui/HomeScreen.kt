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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

@Composable
fun HomeScreen(
    settings: AppSettings,
    tokenProvider: suspend () -> String,
    onOpenTasks: () -> Unit,
    onOpenBoard: () -> Unit,
    onOpenSettings: () -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
) {
    var reloadKey by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onKeepScreenOn(true)
        onDispose { onKeepScreenOn(false) }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Home")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onOpenTasks,
                    modifier = Modifier.weight(1f).height(64.dp),
                ) { Text("Tasks") }
                OutlinedButton(
                    onClick = onOpenBoard,
                    modifier = Modifier.weight(1f).height(64.dp),
                ) { Text("Board") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f).height(64.dp),
                ) { Text("Settings") }
                OutlinedButton(
                    onClick = { reloadKey += 1 },
                    modifier = Modifier.weight(1f).height(64.dp),
                ) { Text("Reload") }
            }
        }
        HomeAssistantDashboardPane(
            settings = settings,
            tokenProvider = tokenProvider,
            reloadKey = reloadKey,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HomeAssistantDashboardPane(
    settings: AppSettings,
    tokenProvider: suspend () -> String,
    reloadKey: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var token by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        token = tokenProvider().ifBlank { null }
    }

    val dashboardUrl = remember(settings.homeAssistantUrl, settings.dashboardPath) {
        settings.homeAssistantUrl.normalizedBaseUrl() +
            settings.dashboardPath.trim().trim('/').ifBlank { "lovelace/0" } +
            "?external_auth=1"
    }

    LaunchedEffect(reloadKey, token, dashboardUrl) {
        if (token != null && reloadKey > 0) {
            error = null
            webView?.loadUrl(dashboardUrl)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (token == null) {
            Text(
                "Save a Home Assistant URL and token in Settings. Build a black-and-white Lovelace dashboard for this tablet, then set its path in Settings.",
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
                Button(
                    onClick = {
                        error = null
                        webView?.loadUrl(dashboardUrl)
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Retry") }
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
