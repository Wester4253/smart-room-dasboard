package com.example.smartroomdashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.smartroomdashboard.ocr.OcrEngine

private enum class AppScreen { Todos, Board, Dashboard, Settings, Handwriting }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SmartRoomApp(viewModel: TodoViewModel, ocrEngine: OcrEngine) {
    SmartRoomTheme {
        val state by viewModel.state.collectAsStateWithLifecycle()
        var screen by remember { mutableStateOf(AppScreen.Todos) }
        var handwritingReturn by remember { mutableStateOf<(String) -> Unit>({}) }
        var handwritingAddsDirectly by remember { mutableStateOf(true) }

        Column(Modifier.fillMaxSize().background(Color.White)) {
            if (screen != AppScreen.Dashboard && screen != AppScreen.Settings) {
                ModeBar(screen) { selected ->
                    if (selected == AppScreen.Handwriting) handwritingAddsDirectly = true
                    screen = selected
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (screen) {
                    AppScreen.Todos -> TodoListScreen(
                        state = state,
                        onRefresh = viewModel::refresh,
                        onAdd = viewModel::add,
                        onUpdate = viewModel::update,
                        onDelete = viewModel::delete,
                        onToggle = viewModel::toggle,
                        onMessageShown = viewModel::clearMessage,
                        onHandwriting = { callback ->
                            handwritingAddsDirectly = false
                            handwritingReturn = callback
                            screen = AppScreen.Handwriting
                        },
                        contentPadding = PaddingValues(0.dp),
                    )
                    AppScreen.Board -> KanbanBoardScreen(
                        todos = state.todos,
                        isBusy = state.isBusy,
                        onRefresh = viewModel::refresh,
                        onToggle = viewModel::toggle,
                        onAdd = viewModel::add,
                        contentPadding = PaddingValues(0.dp),
                    )
                    AppScreen.Handwriting -> HandwritingScreen(
                        ocrEngine = ocrEngine,
                        onBack = { screen = AppScreen.Todos },
                        onText = {
                            if (handwritingAddsDirectly) viewModel.add(it) else handwritingReturn(it)
                            screen = AppScreen.Todos
                        },
                    )
                    AppScreen.Dashboard -> DashboardScreen(
                        settings = state.settings,
                        tokenProvider = viewModel::dashboardToken,
                        onBack = { screen = AppScreen.Todos },
                    )
                    AppScreen.Settings -> SettingsScreen(
                        settings = state.settings,
                        onBack = { screen = AppScreen.Todos },
                        onSave = { url, entity, token, dashboardPath ->
                            viewModel.saveSettings(url, entity, token, dashboardPath)
                            screen = AppScreen.Todos
                        },
                        onTest = { url, token -> viewModel.testConnectionWith(url, token) },
                        onDiscover = viewModel::discoverTodoEntities,
                        testResult = state.message,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeBar(current: AppScreen, onSelect: (AppScreen) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            AppScreen.Todos to "Tasks",
            AppScreen.Board to "Board",
            AppScreen.Handwriting to "Write",
            AppScreen.Dashboard to "Dashboard",
            AppScreen.Settings to "Settings",
        ).forEach { (target, label) ->
            val modifier = Modifier.weight(1f)
            if (current == target) {
                Button(onClick = { onSelect(target) }, modifier = modifier) { Text(label) }
            } else {
                OutlinedButton(onClick = { onSelect(target) }, modifier = modifier) { Text(label) }
            }
        }
    }
}
