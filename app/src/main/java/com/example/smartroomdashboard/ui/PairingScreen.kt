package com.example.smartroomdashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartroomdashboard.data.remote.PairingServer
import com.example.smartroomdashboard.domain.PairingPayload
import com.example.smartroomdashboard.domain.isNabuCasaHost

/**
 * "Press one button in Home Assistant" setup.
 *
 * The tablet has no camera, so a QR code is not an option, and a Nabu Casa address
 * is per-account and awkward to read off a screen. So the tablet listens on the LAN
 * and Home Assistant pushes to it.
 *
 * What is pushed is only an address and a list of todo entities, gated on the six
 * digit code below. The token is then minted by the tablet itself through the
 * sign-in flow, so no permanent credential crosses the network.
 *
 * Scope note, because it matters for testing away from home: this only works while
 * the tablet and Home Assistant share a network. From school the tablet is behind
 * NAT with no inbound path, so this screen cannot be used there. That is fine,
 * because pairing here teaches the app the cloud address, which is what makes it
 * work at school afterwards.
 */
@Composable
fun PairingScreen(
    onPaired: (PairingPayload) -> Unit,
    onUseCloudInstead: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Starting the listener…") }
    var problem by remember { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf<String?>(null) }

    val server = remember {
        PairingServer(
            onPairing = { payload -> onPaired(payload) },
            onFailure = { message -> problem = message },
        )
    }

    DisposableEffect(server) {
        if (server.start()) {
            val host = server.localAddress()
            address = if (host == null) null else "http://$host:${server.port}"
            status = if (host == null) {
                "Listening. Connect this network to Home Assistant to finish."
            } else {
                "Listening on $host:${server.port}"
            }
        }
        onDispose { server.stop() }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Pair from Home Assistant", style = MaterialTheme.typography.titleLarge)

        Text(
            "In Home Assistant, add a button and use this action. " +
                "It sends this tablet its address; the tablet then signs in by " +
                "itself, so no token is sent over the network.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        EinkSectionHeader("1. Put this code on the tablet")
        Text(
            server.code,
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        EinkSectionHeader("2. On this network, send it from Home Assistant")
        Text(
            buildString {
                append("Service: boox_smart_room.pair_tablet\n")
                append("host: ").append(server.localAddress() ?: "<this tablet's IP>\n")
                append("port: ").append(server.port).append('\n')
                append("code: ").append(server.code)
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
        )

        if (address != null) {
            EinkNotice(text = "Listening on $address")
        }

        problem?.let { message ->
            EinkNotice(text = message, tone = NoticeTone.ERROR)
        }

        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        EinkOutlinedButton(
            onClick = onUseCloudInstead,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Use my cloud address instead") }

        EinkOutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Cancel") }
    }
}
