package com.example.smartroomdashboard.ui

import android.annotation.SuppressLint
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.smartroomdashboard.domain.ONBOARDING_CALLBACK
import com.example.smartroomdashboard.domain.OnboardingPayload
import com.example.smartroomdashboard.domain.homeAssistantLoginUrl
import com.example.smartroomdashboard.domain.onboardingScript
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Bridges the injected onboarding script back into Kotlin.
 *
 * Everything arrives as a single JSON string so the JS side stays trivial, and
 * both methods are called on the WebView's JavaScript thread, so results are
 * posted to the main thread before touching state.
 */
private class OnboardingBridge(
    private val onPayload: (OnboardingPayload) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    @JavascriptInterface
    fun onResult(json: String) {
        val parsed = parseOnboardingPayload(json)
        if (parsed == null) {
            post { onFailure("Home Assistant returned a response the app could not read.") }
        } else {
            post { onPayload(parsed) }
        }
    }

    @JavascriptInterface
    fun onError(message: String) {
        val text = message.ifBlank { "Home Assistant did not complete the sign-in." }
        post { onFailure(text) }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }
}

/** Parses the JSON handed over by [onboardingScript]. Null when unreadable. */
internal fun parseOnboardingPayload(json: String): OnboardingPayload? = runCatching {
    val root = JSONObject(json)
    val token = root.optString("token")
    if (token.isBlank()) return null
    val entities = mutableListOf<Pair<String, String>>()
    val array: JSONArray = root.optJSONArray("todoEntities") ?: JSONArray()
    for (index in 0 until array.length()) {
        val pair = array.optJSONArray(index) ?: continue
        val entityId = pair.optString(0)
        if (entityId.isBlank()) continue
        entities += entityId to pair.optString(1).ifBlank { entityId }
    }
    OnboardingPayload(
        token = token,
        locationName = root.optString("locationName"),
        version = root.optString("version"),
        todoEntities = entities,
    )
}.getOrNull()

/** How far the sign-in has got, for the status line above the page. */
enum class OnboardingStep { LOADING, WAITING_FOR_LOGIN, MINTING_TOKEN, DONE, FAILED }

/**
 * One-tap Home Assistant setup.
 *
 * We open Home Assistant's own login page in a WebView rather than collecting a
 * password, so the credential only ever reaches Home Assistant. Once the page is
 * authenticated, [onboardingScript] runs inside that origin and mints a long-lived
 * token over the WebSocket API (`auth/long_lived_access_token`), then reads
 * `/api/config` and `/api/states` so the caller can fill in every field.
 *
 * The app's own `HomeAssistantAuthBridge` on the dashboard screen is a separate
 * mechanism and is untouched by this.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OnboardingScreen(
    baseUrl: String,
    onAuthenticated: (OnboardingPayload) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var step by remember(baseUrl) { mutableStateOf(OnboardingStep.LOADING) }
    var failure by remember(baseUrl) { mutableStateOf<String?>(null) }
    val webViewRef = remember(baseUrl) { mutableStateOf<WebView?>(null) }
    // Guards against the script reporting twice (e.g. a late error after success).
    val finished = remember(baseUrl) { mutableStateOf(false) }

    val loginUrl = remember(baseUrl) { homeAssistantLoginUrl(baseUrl) }

    // Poll until the page exposes a session, then hand over to the script. The
    // login page is a full SPA, so there is no navigation callback to hook.
    LaunchedEffect(baseUrl) {
        while (!finished.value && step != OnboardingStep.DONE && step != OnboardingStep.FAILED) {
            delay(PROBE_INTERVAL_MS)
            val view = webViewRef.value ?: continue
            if (step == OnboardingStep.LOADING) step = OnboardingStep.WAITING_FOR_LOGIN
            val answer = runCatching { view.evaluateJavascriptOnce(READ_SESSION_SCRIPT) }
                .getOrNull()
            if (answer?.contains("ready") == true && !finished.value) {
                step = OnboardingStep.MINTING_TOKEN
                val launched = runCatching { view.evaluateJavascript(onboardingScript(), null) }
                if (launched.isFailure) {
                    finished.value = true
                    step = OnboardingStep.FAILED
                    failure = "Could not run the sign-in helper: ${launched.exceptionOrNull()?.message}"
                }
            }
        }
    }

    // The WebView leaks an Activity-sized context if it is not destroyed when the
    // composable leaves; the old dashboard pane never did this.
    DisposableEffect(baseUrl) {
        onDispose {
            finished.value = true
            webViewRef.value?.let { view ->
                view.stopLoading()
                view.webViewClient = WebViewClient()
                view.removeJavascriptInterface(ONBOARDING_CALLBACK)
                view.destroy()
            }
            webViewRef.value = null
        }
    }

    Column(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Connect to Home Assistant", style = MaterialTheme.typography.titleLarge)
            Text(
                text = when (step) {
                    OnboardingStep.LOADING -> "Opening $loginUrl"
                    OnboardingStep.WAITING_FOR_LOGIN ->
                        "Sign in with your Home Assistant account. " +
                            "Your password goes to Home Assistant, not to this app."
                    OnboardingStep.MINTING_TOKEN ->
                        "Signed in. Creating a long-lived access token and reading your todo lists."
                    OnboardingStep.DONE -> "Connected."
                    OnboardingStep.FAILED -> "Setup did not finish."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EinkOutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
        }

        failure?.let { message ->
            EinkNotice(
                text = message,
                tone = NoticeTone.ERROR,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = {
                WebView(context).also { view ->
                    view.settings.javaScriptEnabled = true
                    view.settings.domStorageEnabled = true
                    view.overScrollMode = WebView.OVER_SCROLL_NEVER
                    view.addJavascriptInterface(
                        OnboardingBridge(
                            onPayload = { payload ->
                                if (finished.value) return@OnboardingBridge
                                finished.value = true
                                step = OnboardingStep.DONE
                                onAuthenticated(payload)
                            },
                            onFailure = { message ->
                                if (finished.value) return@OnboardingBridge
                                finished.value = true
                                step = OnboardingStep.FAILED
                                failure = message
                            },
                        ),
                        ONBOARDING_CALLBACK,
                    )
                    view.webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (!request.isForMainFrame) return
                            if (finished.value) return
                            finished.value = true
                            step = OnboardingStep.FAILED
                            failure = "Could not reach $baseUrl (${error.description}). " +
                                "Check the address and that Home Assistant is reachable."
                        }
                    }
                    view.loadUrl(loginUrl)
                    webViewRef.value = view
                }
            },
            update = {},
        )
    }
}

private const val PROBE_INTERVAL_MS = 900L

/**
 * [WebView.evaluateJavascript] with a suspending wrapper.
 *
 * The callback is invoked on the WebView's JavaScript thread, so the continuation
 * is resumed with a dispatcher hop rather than touching Compose state directly.
 */
private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

private suspend fun WebView.evaluateJavascriptOnce(script: String): String? =
    suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { value ->
            mainHandler.post {
                if (continuation.isActive) continuation.resume(value)
            }
        }
    }

/**
 * Reports whether the page holds a usable session.
 *
 * Kept deliberately tiny: it runs on a timer, so it must not allocate much or
 * touch the network.
 */
private val READ_SESSION_SCRIPT = """
(function () {
  try {
    var el = document.querySelector('home-assistant');
    if (el && el.hass && el.hass.auth && el.hass.auth.data &&
        el.hass.auth.data.refresh_token) {
      return 'ready';
    }
  } catch (e) {}
  for (var i = 0; i < localStorage.length; i++) {
    try {
      var raw = localStorage.getItem(localStorage.key(i));
      if (typeof raw === 'string' && raw.indexOf('refresh_token') >= 0) {
        return 'ready';
      }
    } catch (e) {}
  }
  return 'waiting';
})();
""".trimIndent()
