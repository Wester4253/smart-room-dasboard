package com.example.smartroomdashboard.data.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.example.smartroomdashboard.domain.normalizedBaseUrl

/**
 * A Home Assistant instance seen on the local network.
 *
 * [url] is the address the app should actually talk to, already normalized.
 */
data class DiscoveredInstance(
    val name: String,
    val url: String,
    val version: String,
    val locationName: String,
) {
    val label: String
        get() = if (locationName.isNotBlank() && locationName != name) {
            "$locationName ($url)"
        } else {
            url
        }
}

/**
 * Finds Home Assistant instances with mDNS.
 *
 * Home Assistant's `zeroconf` component advertises `_home-assistant._tcp` with TXT
 * records `location_name`, `version`, `internal_url`, `external_url` and the
 * instance UUID (see `homeassistant/components/zeroconf/__init__.py`). That is
 * enough to connect without the user typing or reading out an address, which is
 * the whole point: a Nabu Casa remote UI host is per-account and the user cannot
 * be expected to copy it off a settings page.
 *
 * Only works on the same LAN, so this is the *local* half of the problem. The
 * cloud half is handled by [fetchCloudRemoteUrl], which asks an already-connected
 * instance for its own `remote_domain`.
 */
class HaDiscovery(private val context: Context) {

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    /**
     * Browse for a short while and return whatever turned up.
     *
     * Browsing is a broadcast, not a query, so this collects for [BROWSE_MILLIS]
     * rather than returning a single answer. Callers should treat an empty result
     * as "nothing found", not "no instances exist".
     */
    suspend fun discover(timeoutMillis: Long = BROWSE_MILLIS): List<DiscoveredInstance> {
        val manager = nsdManager ?: return emptyList()
        val found = LinkedHashMap<String, DiscoveredInstance>()

        val listener = object : NsdManager.DiscoveryListener {
            private val resolved = mutableSetOf<String>()

            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery could not start ($type, $errorCode)")
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit

            override fun onDiscoveryStarted(type: String) = Unit

            override fun onDiscoveryStopped(type: String) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                if (resolved.add(service.serviceName)) {
                    @Suppress("DEPRECATION")
                    manager.resolveService(
                        service,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                                Log.w(TAG, "could not resolve ${info.serviceName} ($code)")
                            }

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                info.toInstance()?.let { found[it.url] = it }
                            }
                        },
                    )
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                found.remove(service.serviceName)
            }
        }

        return try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            kotlinx.coroutines.delay(timeoutMillis)
            found.values.toList()
        } catch (e: IllegalArgumentException) {
            // Thrown when a discovery is already in progress on some firmware.
            Log.w(TAG, "mDNS discovery rejected", e)
            emptyList()
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    private fun NsdServiceInfo.toInstance(): DiscoveredInstance? {
        val attributes = attributes
        fun txt(key: String): String =
            attributes[key]?.toString()?.trim().orEmpty()

        val host = host?.hostAddress?.takeIf { it.isNotBlank() } ?: return null
        val advertised = txt("internal_url").ifBlank { txt("external_url") }
        val url = when {
            // Prefer what Home Assistant advertises: it already knows its own
            // port and any path prefix.
            advertised.startsWith("http") -> advertised.normalizedBaseUrl()
            port > 0 -> "http://$host:$port/".normalizedBaseUrl()
            else -> return null
        }
        return DiscoveredInstance(
            name = serviceName?.substringBefore('.') ?: host,
            url = url,
            version = txt("version"),
            locationName = txt("location_name"),
        )
    }

    private companion object {
        const val TAG = "HaDiscovery"
        const val SERVICE_TYPE = "_home-assistant._tcp."
        const val BROWSE_MILLIS = 2_500L
    }
}

/**
 * The cloud commands this app needs, kept as constants so the WebSocket layer and
 * the tests agree on the wire format.
 *
 * `cloud/status` is registered by Home Assistant's `cloud` component and returns
 * `remote_domain` (the `<id>.ui.nabu.casa` host) plus `remote_connected`. It is
 * admin-only, which is fine: the token minted during setup belongs to the user who
 * signed in, and managing todo lists already requires an admin account.
 */
object CloudCommands {
    const val STATUS = "cloud/status"
}

/** What [CloudCommands.STATUS] tells us about the account's remote access. */
data class CloudInfo(
    val loggedIn: Boolean,
    val remoteDomain: String,
    val remoteConnected: Boolean,
) {
    /** The cloud address to use, or null when remote access is unavailable. */
    val remoteUrl: String?
        get() = if (loggedIn && remoteDomain.isNotBlank()) {
            "https://$remoteDomain"
        } else {
            null
        }
}
