package com.example.smartroomdashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.smartroomdashboard.data.remote.TodoEntity
import com.example.smartroomdashboard.domain.AppSettings
import com.example.smartroomdashboard.domain.TEXT_SCALE_STEPS
import com.example.smartroomdashboard.domain.isNabuCasaHost
import com.example.smartroomdashboard.domain.parseBoardEntityIds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    todoLists: List<TodoEntity> = emptyList(),
    onHome: () -> Unit,
    // SmartRoomApp navigates home from settings, so back defaults to the same target.
    onBack: () -> Unit = onHome,
    onSave: (
        url: String,
        entityId: String,
        token: String,
        dashboardPath: String,
        boardEntityIds: List<String>,
    ) -> Unit,
    onTest: (url: String, token: String) -> Unit,
    onDiscover: () -> Unit,
    onSavePreferences: (textScale: Float, keepScreenOn: Boolean) -> Unit = { _, _ -> },
    onStartSetup: () -> Unit = {},
    onRevertToLocal: () -> Unit = {},
    testResult: String? = null,
) {
    var url by remember(settings.homeAssistantUrl) { mutableStateOf(settings.homeAssistantUrl) }
    var entityId by remember(settings.todoEntityId) { mutableStateOf(settings.todoEntityId) }
    var dashboardPath by remember(settings.dashboardPath) { mutableStateOf(settings.dashboardPath) }
    var boardIds by remember(settings.boardEntityIds) {
        mutableStateOf(settings.boardEntityIds.joinToString("\n"))
    }
    // Intentionally never rehydrated: the stored token is write-only from the UI's
    // point of view, and a blank field means "keep what is saved".
    var token by remember { mutableStateOf("") }
    var textScale by remember(settings.textScale) { mutableStateOf(settings.textScale) }
    var keepScreenOn by remember(settings.keepScreenOn) { mutableStateOf(settings.keepScreenOn) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onHome) { Text("Home") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EinkSectionHeader("Recommended")
            Text(
                "Sign in once and the app fills in the address, creates a long-lived " +
                    "token and finds your todo lists. Your password goes to Home " +
                    "Assistant, never to this app.",
                style = MaterialTheme.typography.bodyMedium,
            )
            EinkButton(
                onClick = onStartSetup,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Set up with Home Assistant") }

            EinkSectionHeader("Connection")
            if (settings.homeAssistantUrl.isNabuCasaHost()) {
                EinkNotice(
                    text = "Using your Home Assistant Cloud address " +
                        settings.homeAssistantUrl + ", so this works off your home network.",
                )
                if (settings.cloudFallbackUrl.isNotBlank()) {
                    EinkOutlinedButton(
                        onClick = onRevertToLocal,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Use ${settings.cloudFallbackUrl} instead") }
                }
            } else {
                Text(
                    "Connected on this network only. If you have a Home Assistant " +
                        "subscription, run setup again to switch to your cloud address " +
                        "automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Create a list in Home Assistant first: Settings > Devices & services > " +
                    "Add integration > Local Todo. The token is encrypted with Android " +
                    "Keystore and is never shown after saving.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Home Assistant URL") },
                placeholder = { Text("http://homeassistant.local:8123") },
                singleLine = true,
            )
            OutlinedTextField(
                value = entityId,
                onValueChange = { entityId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Todo entity ID") },
                placeholder = { Text("todo.smart_room") },
                singleLine = true,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Long-lived access token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            OutlinedTextField(
                value = dashboardPath,
                onValueChange = { dashboardPath = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Dashboard path") },
                placeholder = { Text("lovelace/0") },
                supportingText = {
                    Text("Relative to your Home Assistant URL; no leading slash.")
                },
                singleLine = true,
            )
            OutlinedTextField(
                value = boardIds,
                onValueChange = { boardIds = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Board entity IDs") },
                placeholder = { Text("todo.kitchen\ntodo.garage") },
                supportingText = {
                    Text("One todo.* entity per line. Each becomes its own board column.")
                },
                minLines = 2,
            )
            EinkSectionHeader("Your todo lists")
            if (todoLists.isEmpty()) {
                Text(
                    "No todo.* entities found yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                todoLists.forEach { entity ->
                    val selected = entity.entityId == entityId || boardIds.contains(entity.entityId)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EinkOutlinedButton(
                            onClick = {
                                boardIds = if (selected) {
                                    parseBoardEntityIds(boardIds).minus(entity.entityId)
                                        .joinToString("\n")
                                } else {
                                    parseBoardEntityIds(boardIds).plus(entity.entityId)
                                        .joinToString("\n")
                                }
                            },
                            modifier = Modifier.weight(1f),
                            height = 52.dp,
                        ) {
                            Text(
                                if (selected) "- ${entity.name}" else "+ ${entity.name}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            EinkSectionHeader("Display")
            Text(
                "Text size",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TEXT_SCALE_STEPS.forEach { step ->
                    val selected = kotlin.math.abs(step - textScale) < 0.01f
                    EinkButton(
                        onClick = {
                            textScale = step
                            onSavePreferences(step, keepScreenOn)
                        },
                        enabled = !selected,
                        modifier = Modifier.weight(1f),
                        height = 52.dp,
                    ) {
                        Text(
                            "${(step * 100).toInt()}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Checkbox(
                    checked = keepScreenOn,
                    onCheckedChange = {
                        keepScreenOn = it
                        onSavePreferences(textScale, it)
                    },
                )
                Column {
                    Text("Keep the screen on", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Prevents the tablet sleeping while the dashboard is up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            EinkSectionHeader("Actions")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EinkOutlinedButton(
                    onClick = onDiscover,
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Find todo lists") }

                EinkOutlinedButton(
                    onClick = { onTest(url, token) },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Test connection") }

                EinkButton(
                    onClick = { onSave(url, entityId, token, dashboardPath, parseBoardEntityIds(boardIds)) },
                    enabled = url.isNotBlank() && entityId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save settings")
                }

                testResult?.let { result ->
                    EinkNotice(text = result)
                }
            }
        }
    }
}
