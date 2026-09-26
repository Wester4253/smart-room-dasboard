package com.example.smartroomdashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.smartroomdashboard.domain.OnboardingPayload
import com.example.smartroomdashboard.domain.deckEntityIds
import com.example.smartroomdashboard.domain.isPlausibleBaseUrl
import com.example.smartroomdashboard.ocr.OcrEngine

private enum class AppScreen {
    Home, Todos, Board, Settings, Handwriting, CardDetail, Setup, SetupPrompt, Pairing,
}

@Composable
fun SmartRoomApp(
    viewModel: TodoViewModel,
    ocrEngine: OcrEngine,
    onKeepScreenOn: (Boolean) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // rememberSaveable so a rotation or a process death mid-task does not dump the
    // user back on the home screen.
    var screenName by rememberSaveable { mutableStateOf(AppScreen.Home.name) }
    var handwritingReturnName by rememberSaveable { mutableStateOf(AppScreen.Todos.name) }
    var selectedTodoId by rememberSaveable { mutableStateOf<String?>(null) }
    // Where the card detail screen was opened from, so Back and Delete return
    // there instead of always jumping to the board.
    var detailOriginName by rememberSaveable { mutableStateOf(AppScreen.Board.name) }
    var setupUrl by rememberSaveable { mutableStateOf("") }

    val screen = remember(screenName) { AppScreen.valueOf(screenName) }
    val handwritingReturnScreen = remember(handwritingReturnName) {
        AppScreen.valueOf(handwritingReturnName)
    }
    val detailOrigin = remember(detailOriginName) { AppScreen.valueOf(detailOriginName) }

    // A lambda cannot be saved across process death, so this stays in `remember`.
    var handwritingReturn by remember { mutableStateOf<(String) -> Unit>({}) }

    val selectedTodo = state.todos.firstOrNull { it.id == selectedTodoId }

    // Keep-awake is driven by settings now rather than by HomeScreen's
    // DisposableEffect, which used to be a no-op because MainActivity never
    // supplied the callback.
    LaunchedEffect(state.settings.keepScreenOn, onKeepScreenOn) {
        onKeepScreenOn(state.settings.keepScreenOn)
    }

    // A successful setup returns to the home screen.
    LaunchedEffect(state.settings.isConfigured) {
        if (state.settings.isConfigured && screenName == AppScreen.Setup.name) {
            screenName = AppScreen.Home.name
        }
    }

    // Opening setup on an unconfigured install should immediately look for
    // instances rather than waiting for a tap.
    LaunchedEffect(screenName) {
        if (screenName == AppScreen.Setup.name || screenName == AppScreen.SetupPrompt.name) {
            viewModel.discoverInstances()
        }
    }

    SmartRoomTheme(textScale = state.settings.textScale) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (screen) {
                    AppScreen.Home -> HomeScreen(
                        settings = state.settings,
                        tokenProvider = viewModel::dashboardToken,
                        onOpenTasks = { screenName = AppScreen.Todos.name },
                        onOpenBoard = { screenName = AppScreen.Board.name },
                        onOpenSettings = { screenName = AppScreen.Settings.name },
                        onOpenSetup = { setupUrl = state.settings.homeAssistantUrl; screenName = AppScreen.Setup.name },
                        onKeepScreenOn = onKeepScreenOn,
                    )

                    AppScreen.Todos -> TodoListScreen(
                        state = state,
                        onHome = { screenName = AppScreen.Home.name },
                        onRefresh = viewModel::refresh,
                        onAdd = { title, description -> viewModel.add(title, description) },
                        onOpen = { todo ->
                            selectedTodoId = todo.id
                            detailOriginName = AppScreen.Todos.name
                            screenName = AppScreen.CardDetail.name
                        },
                        onToggle = viewModel::toggle,
                        onDelete = viewModel::delete,
                        onMessageShown = viewModel::clearMessage,
                        onHandwriting = { callback ->
                            handwritingReturn = callback
                            handwritingReturnName = AppScreen.Todos.name
                            screenName = AppScreen.Handwriting.name
                        },
                        contentPadding = PaddingValues(0.dp),
                    )

                    AppScreen.Board -> KanbanBoardScreen(
                        state = state,
                        onHome = { screenName = AppScreen.Home.name },
                        onRefresh = viewModel::refresh,
                        onOpen = { todo ->
                            selectedTodoId = todo.id
                            detailOriginName = AppScreen.Board.name
                            screenName = AppScreen.CardDetail.name
                        },
                        onAdd = { title, description, listId ->
                            viewModel.add(title, description, listId)
                        },
                        onHandwriting = { callback ->
                            handwritingReturn = callback
                            handwritingReturnName = AppScreen.Board.name
                            screenName = AppScreen.Handwriting.name
                        },
                        contentPadding = PaddingValues(0.dp),
                    )

                    AppScreen.CardDetail -> {
                        val todo = selectedTodo
                        if (todo == null) {
                            // The card was deleted or the cache rotated under us.
                            screenName = AppScreen.Todos.name
                        } else {
                            CardDetailScreen(
                                todo = todo,
                                decks = run {
                                    val names = state.todoLists.associate { it.entityId to it.name }
                                    state.settings.deckEntityIds().map { id ->
                                        id to names.getOrElse(id) { id }
                                    }
                                },
                                onHome = { screenName = AppScreen.Home.name },
                                onBack = { screenName = detailOriginName },
                                onSave = { updated ->
                                    viewModel.update(updated)
                                    selectedTodoId = updated.id
                                },
                                onToggle = { viewModel.toggle(todo) },
                                onDelete = {
                                    viewModel.delete(todo)
                                    screenName = detailOriginName
                                },
                                onMove = { dest -> viewModel.move(todo, dest) },
                                onHandwriting = { callback ->
                                    handwritingReturn = callback
                                    handwritingReturnName = AppScreen.CardDetail.name
                                    screenName = AppScreen.Handwriting.name
                                },
                                onMessage = viewModel::clearMessage,
                            )
                        }
                    }

                    AppScreen.Handwriting -> HandwritingScreen(
                        ocrEngine = ocrEngine,
                        onHome = { screenName = AppScreen.Home.name },
                        onBack = { screenName = handwritingReturnName },
                        onText = {
                            handwritingReturn(it)
                            screenName = handwritingReturnName
                        },
                    )

                    AppScreen.Settings -> SettingsScreen(
                        settings = state.settings,
                        todoLists = state.todoLists,
                        onHome = { screenName = AppScreen.Home.name },
                        onSave = { url, entity, token, dashboardPath, boardIds ->
                            viewModel.saveSettings(url, entity, token, dashboardPath, boardIds)
                            screenName = AppScreen.Home.name
                        },
                        onTest = { url, token -> viewModel.testConnectionWith(url, token) },
                        onDiscover = viewModel::discoverTodoEntities,
                        onSavePreferences = viewModel::savePreferences,
                        onStartSetup = {
                            // An already-configured install re-opens straight into
                            // the sign-in flow, since it has an address to use.
                            if (state.settings.homeAssistantUrl.isNotBlank()) {
                                setupUrl = state.settings.homeAssistantUrl
                                screenName = AppScreen.Setup.name
                            } else {
                                screenName = AppScreen.SetupPrompt.name
                            }
                        },
                        onRevertToLocal = viewModel::revertToLocal,
                        testResult = state.message,
                    )

                    AppScreen.Setup -> {
                        val url = setupUrl
                        if (!url.isPlausibleBaseUrl()) {
                            // Nothing to open yet. Offer what is on the network
                            // before falling back to typing an address.
                            SetupPromptScreen(
                                state = state,
                                onStart = { setupUrl = it; viewModel.beginSetup() },
                                onPair = { screenName = AppScreen.Pairing.name },
                                onRediscover = viewModel::discoverInstances,
                                onCancel = { screenName = AppScreen.Settings.name },
                            )
                        } else {
                            OnboardingScreen(
                                baseUrl = url,
                                onAuthenticated = { payload: OnboardingPayload ->
                                    viewModel.completeOnboarding(
                                        baseUrl = url,
                                        token = payload.token,
                                        todoEntityIds = payload.todoEntities,
                                        current = state.settings,
                                    )
                                    screenName = AppScreen.Home.name
                                },
                                onCancel = { screenName = AppScreen.Settings.name },
                            )
                        }
                    }

                    AppScreen.Pairing -> PairingScreen(
                        onPaired = { payload ->
                            // The push only carries the address, so continue into
                            // the normal sign-in flow to mint the token.
                            setupUrl = payload.baseUrl
                            viewModel.beginSetup()
                            screenName = AppScreen.Setup.name
                        },
                        onUseCloudInstead = {
                            setupUrl = state.settings.homeAssistantUrl
                            screenName = AppScreen.SetupPrompt.name
                        },
                        onCancel = { screenName = AppScreen.Settings.name },
                    )

                    AppScreen.SetupPrompt -> SetupPromptScreen(
                        state = state,
                        onStart = { setupUrl = it; viewModel.beginSetup() },
                        onPair = { screenName = AppScreen.Pairing.name },
                        onRediscover = viewModel::discoverInstances,
                        onCancel = { screenName = AppScreen.Settings.name },
                    )
                }
            }
        }

        // Rendered here, once, so a message reaches the user no matter which
        // screen produced it.
        state.message?.let { message ->
            NoticeDialog(message = message, onDismiss = viewModel::clearMessage)
        }
    }
}

/**
 * Asks for the Home Assistant address before the sign-in WebView can open.
 *
 * There is no discovery API for a Nabu Casa remote UI host: the address is
 * per-instance and only Home Assistant knows it. The one thing we can do is
 * recognise the shape of it and point at where the user finds it.
 */
@Composable
private fun SetupPromptScreen(
    state: TodoUiState,
    onStart: (String) -> Unit,
    onPair: () -> Unit,
    onRediscover: () -> Unit,
    onCancel: () -> Unit,
) {
    var url by rememberSaveable(state.settings.homeAssistantUrl) {
        mutableStateOf(state.settings.homeAssistantUrl)
    }
    var showManual by rememberSaveable {
        mutableStateOf(state.settings.homeAssistantUrl.isBlank() && state.discovered.isEmpty())
    }
    val valid = url.isPlausibleBaseUrl()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Connect to Home Assistant", style = MaterialTheme.typography.titleLarge)

        val found = state.discovered
        val online = hasNetwork(context)

        if (found.isEmpty() && !showManual) {
            Text(
                text = if (online) {
                    "Looking for Home Assistant on this network..."
                } else {
                    "This tablet is offline. Connect it to the same Wi-Fi as Home " +
                        "Assistant, then search again."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (found.isNotEmpty()) {
            Text(
                "Tap your instance. No address to type, and no need to copy a " +
                    "Nabu Casa URL from anywhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            found.forEach { instance ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EinkButton(
                        onClick = { onStart(instance.url) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(instance.label) }
                    if (instance.version.isNotBlank()) {
                        Text(
                            "Version ${instance.version}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
            EinkOutlinedButton(
                onClick = onRediscover,
                enabled = !state.isBusy && online,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.isBusy) "Searching…" else "Search again") }
        }

        if (showManual) {
            EinkSectionHeader("Enter an address")
            Text(
                "Only needed if Home Assistant is on a different network, or discovery " +
                    "cannot see it. On your network it looks like " +
                    "http://homeassistant.local:8123",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Home Assistant address") },
                singleLine = true,
            )
            if (!url.isBlank() && !valid) {
                EinkNotice(
                    text = "That does not look like an http:// or https:// address.",
                    tone = NoticeTone.ERROR,
                )
            }
        } else {
            EinkOutlinedButton(
                onClick = { showManual = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enter an address instead") }
        }

        EinkButton(
            onClick = { onStart(url.trim()) },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue") }

        // The best option when Home Assistant and this tablet share a network:
        // nothing to read off a screen and nothing to type.
        EinkButton(
            onClick = onPair,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send from Home Assistant instead") }

        EinkOutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Cancel") }
    }
}

/**
 * Whether the device has a usable network.
 *
 * Checked explicitly because mDNS discovery silently finds nothing when offline,
 * which is indistinguishable from "no Home Assistant on this network" and sends
 * the user hunting for a problem that is not there.
 */
fun hasNetwork(context: android.content.Context): Boolean {
    val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Shared blocking notice. Every screen routes its messages through here. */
@Composable
fun NoticeDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("Smart Room") },
        text = { Text(message) },
    )
}

@Composable
fun HomeButton(onHome: () -> Unit, modifier: Modifier = Modifier) {
    EinkOutlinedButton(
        onClick = onHome,
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp),
    ) { Text("Home") }
}

@Composable
fun ScreenHeader(
    title: String,
    onHome: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HomeButton(onHome)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            trailing?.invoke()
        }
    }
}
