package com.example.smartroomdashboard.domain

/**
 * One-tap onboarding helpers.
 *
 * The flow deliberately never sees the user's Home Assistant password. We load
 * Home Assistant's own login page in a WebView and, once the page is
 * authenticated, inject script that runs *inside that page's origin* and mints a
 * long-lived token over the WebSocket API. See [onboardingScript].
 *
 * Everything in this file is pure Kotlin (no `android.*`) so it is covered by the
 * JVM unit tests.
 */

/** A Nabu Casa remote-UI host, e.g. `https://abcdef12.ui.nabu.casa`. */
private val NABU_CASA_HOST = Regex("""^[A-Za-z0-9-]+\.ui\.nabu\.casa$""")

/** Recognises a Nabu Casa remote UI host so the UI can label it as cloud. */
fun String.isNabuCasaHost(): Boolean {
    val normalized = trim().removeSuffix("/").removeSuffix("/api")
    val host = normalized.substringAfter("://", normalized).substringBefore('/')
    return NABU_CASA_HOST.matches(host.lowercase())
}

/** True when the URL looks like something we can reasonably point a WebView at. */
fun String.isPlausibleBaseUrl(): Boolean {
    val trimmed = trim()
    if (trimmed.isEmpty()) return false
    val parts = trimmed.split("://", limit = 2)
    if (parts.size != 2) return false
    val scheme = parts[0].lowercase()
    if (scheme != "http" && scheme != "https") return false
    val host = parts[1].substringBefore('/').substringBefore('?')
    if (host.isEmpty() || host.any { it.isWhitespace() }) return false
    // A bare scheme with no host, or a host that is only punctuation, is a typo.
    return host.any { it.isLetterOrDigit() }
}

/**
 * The page to open for login.
 *
 * We deliberately use a real frontend page rather than `/auth/authorize`: the
 * authorize view needs a registered IndieAuth client, which would mean asking the
 * user to register a client by hand. A normal frontend page gives us the login
 * form and, after login, an authenticated page we can script.
 */
fun homeAssistantLoginUrl(baseUrl: String): String =
    baseUrl.normalizedBaseUrl() + "lovelace"

/** Days of validity requested for the minted long-lived token (10 years). */
const val TOKEN_LIFESPAN_DAYS = 3650

/** Name shown in Home Assistant's token list so the user can revoke it later. */
const val TOKEN_CLIENT_NAME = "Smart Room Dashboard"

/** What the injected script hands back to Kotlin once everything is fetched. */
data class OnboardingPayload(
    val token: String,
    val locationName: String,
    val version: String,
    val todoEntities: List<Pair<String, String>>,
)

/**
 * Script injected into the authenticated Home Assistant page.
 *
 * Sequence, all inside the page's own origin:
 *  1. Open `/api/websocket` and authenticate with the page's *refresh* token.
 *  2. `auth/long_lived_access_token` -> the permanent token.
 *  3. `GET /api/config` and `GET /api/states` using that token, so the app can
 *     fill in every field without a second round trip.
 *
 * The refresh token is read from the frontend's own auth store. Both access paths
 * are tried because the shape differs across frontend versions.
 *
 * Every failure path calls `onboardingError(...)` so the UI can explain what went
 * wrong instead of hanging.
 */
fun onboardingScript(): String = """
(function () {
  function reportError(message) {
    try { window.$ONBOARDING_CALLBACK.onError(String(message)); } catch (e) {}
  }
  function reportResult(json) {
    try { window.$ONBOARDING_CALLBACK.onResult(json); } catch (e) {}
  }

  function readRefreshToken() {
    try {
      var el = document.querySelector('home-assistant');
      if (el && el.hass && el.hass.auth && el.hass.auth.data) {
        var rt = el.hass.auth.data.refresh_token;
        if (rt) return rt;
      }
    } catch (e) {}
    // Older frontends keep the token pair in local storage instead.
    for (var i = 0; i < localStorage.length; i++) {
      var key = localStorage.key(i);
      if (!key) continue;
      try {
        var raw = localStorage.getItem(key);
        if (typeof raw !== 'string' || raw.indexOf('refresh_token') < 0) continue;
        var parsed = JSON.parse(raw);
        if (parsed && typeof parsed.refresh_token === 'string' && parsed.refresh_token) {
          return parsed.refresh_token;
        }
      } catch (e) {}
    }
    return null;
  }

  function fetchJson(path, token) {
    return fetch(path, { headers: { 'Authorization': 'Bearer ' + token } })
      .then(function (resp) {
        if (!resp.ok) throw new Error(path + ' returned HTTP ' + resp.status);
        return resp.json();
      });
  }

  var refreshToken = readRefreshToken();
  if (!refreshToken) {
    reportError('Could not read a Home Assistant session from the page. ' +
      'Log in and try again.');
    return;
  }

  var scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
  var socket;
  try {
    socket = new WebSocket(scheme + location.host + '/api/websocket');
  } catch (e) {
    reportError('Could not open the Home Assistant WebSocket: ' + e.message);
    return;
  }

  var done = false;
  var timer = setTimeout(function () {
    if (done) return;
    done = true;
    try { socket.close(); } catch (e) {}
    reportError('Home Assistant did not answer the WebSocket in time.');
  }, 20000);

  socket.onerror = function () {
    if (done) return;
    done = true;
    clearTimeout(timer);
    reportError('The Home Assistant WebSocket connection failed.');
  };

  socket.onclose = function () {
    if (done) return;
    done = true;
    clearTimeout(timer);
    reportError('The Home Assistant WebSocket closed unexpectedly.');
  };

  socket.onopen = function () {
    socket.send(JSON.stringify({ type: 'auth', access_token: refreshToken }));
  };

  socket.onmessage = function (event) {
    var message;
    try {
      message = JSON.parse(event.data);
    } catch (e) {
      return;
    }

    if (message.type === 'auth_invalid') {
      done = true;
      clearTimeout(timer);
      reportError('Home Assistant rejected the login session. Sign in again.');
      return;
    }

    if (message.type === 'auth_ok') {
      socket.send(JSON.stringify({
        id: 1,
        type: 'auth/long_lived_access_token',
        lifespan: $TOKEN_LIFESPAN_DAYS,
        client_name: '$TOKEN_CLIENT_NAME'
      }));
      return;
    }

    if (message.type === 'result' && message.id === 1) {
      // The token-minting reply. `success: false` carries the reason in `error`.
      if (!message.success) {
        done = true;
        clearTimeout(timer);
        var error = (message.error && message.error.message) || 'unknown error';
        reportError('Home Assistant refused to create a token: ' + error);
        return;
      }
      var token = message.result;
      if (typeof token !== 'string' || !token) {
        done = true;
        clearTimeout(timer);
        reportError('Home Assistant returned an empty token.');
        return;
      }
      // The token exists now; collect the rest of the configuration with it.
      Promise.all([fetchJson('/api/config', token), fetchJson('/api/states', token)])
        .then(function (results) {
          var config = results[0] || {};
          var states = results[1] || [];
          var entities = [];
          for (var i = 0; i < states.length; i++) {
            var state = states[i];
            if (!state || !state.entity_id) continue;
            if (state.entity_id.indexOf('todo.') !== 0) continue;
            var attributes = state.attributes || {};
            var name = attributes.friendly_name;
            if (typeof name !== 'string' || !name) name = state.entity_id;
            entities.push([state.entity_id, name]);
          }
          done = true;
          clearTimeout(timer);
          try { socket.close(); } catch (e) {}
          reportResult(JSON.stringify({
            token: token,
            locationName: config.location_name || '',
            version: config.version || '',
            todoEntities: entities
          }));
        })
        .catch(function (error) {
          done = true;
          clearTimeout(timer);
          reportError('Signed in, but reading the configuration failed: ' + error.message);
        });
      return;
    }
  };
})();
""".trimIndent()

/** Name the Kotlin side registers the injected script's callbacks under. */
const val ONBOARDING_CALLBACK = "SmartRoomOnboarding"

/**
 * Picks the todo list to make the default, preferring an exact entity-id match
 * over the first discovered list so a re-run does not silently repoint the board.
 */
fun choosePrimaryTodoEntity(
    todoEntities: List<Pair<String, String>>,
    currentEntityId: String,
): String {
    val entityIds = todoEntities.map { it.first }.filter { it.isNotBlank() }
    if (entityIds.contains(currentEntityId)) return currentEntityId
    return entityIds.firstOrNull().orEmpty()
}
