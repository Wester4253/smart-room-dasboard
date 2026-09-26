package com.example.smartroomdashboard.domain

import com.google.gson.JsonParser

/**
 * Payload pushed from Home Assistant to the tablet.
 *
 * Deliberately contains **no credentials**. The tablet cannot read a Nabu Casa
 * address off a screen and has no camera to scan one, so Home Assistant sends the
 * address instead. The token is then minted by the tablet itself, in the
 * browser-origin sign-in flow, and never travels over the LAN.
 *
 * Adding a token to this payload would be a real regression: it would put a
 * permanent admin credential on the wire in cleartext, reachable by anything
 * that can guess the code.
 */
data class PairingPayload(
    /** Nabu Casa remote UI URL, or a local address. */
    val baseUrl: String,
    /** `entityId to friendly name` for every `todo.*` entity. */
    val todoEntities: List<Pair<String, String>>,
    val locationName: String = "",
) {
    val isNabuCasa: Boolean get() = baseUrl.isNabuCasaHost()
}

/**
 * Parses what the integration POSTs.
 *
 * Returns null for anything unusable so a malformed or hostile push cannot put a
 * bad URL or an empty token list into settings. [expectedCode] must match, which
 * is what stops an unrelated device on the network from provisioning this one.
 */
fun parsePairingPayload(json: String, expectedCode: String): PairingPayload? {
    if (expectedCode.isBlank()) return null
    val root = runCatching {
        JsonParser.parseString(json).asJsonObject
    }.getOrNull() ?: return null

    fun str(key: String): String =
        root.get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    if (str("code") != expectedCode) return null

    val url = str("baseUrl").trim()
    if (!url.isPlausibleBaseUrl()) return null

    val entities = mutableListOf<Pair<String, String>>()
    root.get("todoEntities")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
        val pair = element.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEach
        val entityId = if (pair.size() > 0) {
            pair[0].takeIf { !it.isJsonNull }?.asString.orEmpty().trim()
        } else {
            ""
        }
        // Only todo lists are usable; anything else is dropped rather than stored.
        if (entityId.isBlank() || !entityId.startsWith("todo.")) return@forEach
        val name = if (pair.size() > 1) {
            pair[1].takeIf { !it.isJsonNull }?.asString.orEmpty().trim()
        } else {
            ""
        }
        entities += entityId to name.ifBlank { entityId }
    }

    return PairingPayload(
        baseUrl = url,
        todoEntities = entities,
        locationName = str("locationName").trim(),
    )
}
