package com.example.smartroomdashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.smartroomdashboard.domain.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onSave: (url: String, entityId: String, token: String, dashboardPath: String) -> Unit,
    onTest: (url: String, token: String) -> Unit,
    onDiscover: () -> Unit,
    testResult: String? = null,
) {
    var url by remember(settings.homeAssistantUrl) { mutableStateOf(settings.homeAssistantUrl) }
    var entityId by remember(settings.todoEntityId) { mutableStateOf(settings.todoEntityId) }
    var dashboardPath by remember(settings.dashboardPath) { mutableStateOf(settings.dashboardPath) }
    var token by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
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
            Text(
                "Create a list in Home Assistant first: Settings > Devices & services > Add integration > Local Todo. " +
                    "The token is encrypted with Android Keystore and is never shown after saving.",
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDiscover,
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) { Text("Find todo lists") }

                Button(
                    onClick = { onTest(url, token) },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) { Text("Test connection") }

                Button(
                    onClick = { onSave(url, entityId, token, dashboardPath) },
                    enabled = url.isNotBlank() && entityId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    Text("Save settings")
                }

                testResult?.let { result ->
                    androidx.compose.material3.Text(
                        text = result,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}
