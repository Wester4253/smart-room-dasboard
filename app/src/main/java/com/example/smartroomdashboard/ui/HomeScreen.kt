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
import androidx.compose.material3.MaterialTheme
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
    onOpenSetup: () -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
) {
    var reloadKey by remember { mutableIntStateOf(0) }
    val configured = settings.isConfigured

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Home", style = MaterialTheme.typography.titleLarge)

            if (!configured) {
                // An unconfigured install cannot show the dashboard, so put the
                // one thing that does work in front of the user instead of four
                // buttons that lead to empty screens.
                EinkNotice(
                    text = "Not connected to Home Assistant yet.",
                    tone = NoticeTone.NEUTRAL,
                )
                EinkButton(
                    onClick = onOpenSetup,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Set up Home Assistant") }
                EinkOutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enter details manually") }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EinkButton(
                        onClick = onOpenTasks,
                        modifier = Modifier.weight(1f),
                    ) { Text("Tasks") }
                    EinkButton(
                        onClick = onOpenBoard,
                        modifier = Modifier.weight(1f),
                    ) { Text("Board") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EinkOutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    ) { Text("Settings") }
                    EinkOutlinedButton(
                        onClick = { reloadKey += 1 },
                        modifier = Modifier.weight(1f),
                    ) { Text("Reload") }
                }
            }
        }
        HomeAssistantDashboardPane(
            settings = settings,
            tokenProvider = tokenProvider,
            reloadKey = reloadKey,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }
}

/**
 * The dashboard WebView, with a lifecycle that actually releases it.
 *
 * `AndroidView(onRelease = ...)` is not available in this Compose version, so the
 * destroy is done from a [DisposableEffect] holding the created instance. The old
 * code created a WebView per visit to Home and never destroyed any of them, which
 * leaks the whole WebView (and its Activity context) each time.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DashboardWebView(
    token: String?,
    url: String,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    key: Int = 0,
) {
    val context = LocalContext.current
    val holder = remember(key) { mutableStateOf<WebView?>(null) }

    DisposableEffect(key) {
        onDispose {
            holder.value?.let { view ->
                view.stopLoading()
                view.webViewClient = WebViewClient()
                view.removeJavascriptInterface("externalApp")
                (view.parent as? android.widget.FrameLayout)?.removeView(view)
                view.destroy()
            }
            holder.value = null
        }
    }

    AndroidView(
        modifier = modifier,
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
                            onError("Dashboard failed to load: ${receivedError.description}")
                        }
                    }
                }
                view.addJavascriptInterface(bridge, "externalApp")
                bridge.attach(view)
                view.loadUrl(url)
                holder.value = view
            }
        },
        update = {},
    )
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
    var error by remember { mutableStateOf<String?>(null) }

    val dashboardUrl = remember(settings.homeAssistantUrl, settings.dashboardPath) {
        settings.homeAssistantUrl.normalizedBaseUrl() +
            settings.dashboardPath.trim().trim('/').ifBlank { "lovelace/0" } +
            "?external_auth=1"
    }

    // Keyed on nothing, so this ran exactly once for the life of the composable
    // and never saw a token written after setup. It now re-reads whenever the
    // address or dashboard path changes.
    LaunchedEffect(settings.homeAssistantUrl, settings.dashboardPath) {
        token = tokenProvider().ifBlank { null }
    }

    val currentToken = token
    val currentTokenKey = currentToken.orEmpty()

    // Reload is explicit: `reloadKey` only ever changes when the user taps it.
    // `revision` forces a brand new WebView, which is how a token written after
    // setup takes effect without restarting the app.
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(reloadKey) {
        if (reloadKey > 0) {
            error = null
            revision += 1
        }
    }
    // A token that arrives after onboarding (or is cleared) also needs a new view.
    LaunchedEffect(currentTokenKey) {
        error = null
        revision += 1
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            settings.homeAssistantUrl.isBlank() -> EinkEmptyState(
                title = "No Home Assistant address",
                subtitle = "Set one up and the dashboard appears here. " +
                    "Build a black-and-white Lovelace dashboard for this tablet.",
            )

            currentToken == null -> EinkEmptyState(
                title = "No access token yet",
                subtitle = "Finish setup in Settings and the dashboard will load here.",
            )

            error != null -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EinkNotice(text = error.orEmpty(), tone = NoticeTone.ERROR)
                EinkButton(
                    onClick = { error = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Retry") }
            }

            else -> DashboardWebView(
                key = revision,
                token = currentToken,
                url = dashboardUrl,
                onError = { error = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
