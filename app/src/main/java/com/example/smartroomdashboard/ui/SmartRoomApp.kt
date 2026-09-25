package com.example.smartroomdashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.example.smartroomdashboard.domain.deckEntityIds

private enum class AppScreen { Home, Todos, Board, Settings, Handwriting, CardDetail }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SmartRoomApp(
    viewModel: TodoViewModel,
    ocrEngine: OcrEngine,
    onKeepScreenOn: (Boolean) -> Unit = {},
) {
    SmartRoomTheme {
        val state by viewModel.state.collectAsStateWithLifecycle()
        var screen by remember { mutableStateOf(AppScreen.Home) }
        var handwritingReturn by remember { mutableStateOf<(String) -> Unit>({}) }
        var handwritingReturnScreen by remember { mutableStateOf(AppScreen.Todos) }
        var selectedTodoId by remember { mutableStateOf<String?>(null) }
        val selectedTodo = state.todos.firstOrNull { it.id == selectedTodoId }

        Column(Modifier.fillMaxSize().background(Color.White)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (screen) {
                    AppScreen.Home -> HomeScreen(
                        settings = state.settings,
                        tokenProvider = viewModel::dashboardToken,
                        onOpenTasks = { screen = AppScreen.Todos },
                        onOpenBoard = { screen = AppScreen.Board },
                        onOpenSettings = { screen = AppScreen.Settings },
                        onKeepScreenOn = onKeepScreenOn,
                    )
                    AppScreen.Todos -> TodoListScreen(
                        state = state,
                        onHome = { screen = AppScreen.Home },
                        onRefresh = viewModel::refresh,
                        onAdd = { title, description -> viewModel.add(title, description) },
                        onOpen = { todo ->
                            selectedTodoId = todo.id
                            screen = AppScreen.CardDetail
                        },
                        onToggle = viewModel::toggle,
                        onDelete = viewModel::delete,
                        onMessageShown = viewModel::clearMessage,
                        onHandwriting = { callback ->
                            handwritingReturn = callback
                            handwritingReturnScreen = AppScreen.Todos
                            screen = AppScreen.Handwriting
                        },
                        contentPadding = PaddingValues(0.dp),
                    )
                    AppScreen.Board -> KanbanBoardScreen(
                        state = state,
                        onHome = { screen = AppScreen.Home },
                        onRefresh = viewModel::refresh,
                        onOpen = { todo ->
                            selectedTodoId = todo.id
                            screen = AppScreen.CardDetail
                        },
                        onAdd = { title, description, listId ->
                            viewModel.add(title, description, listId)
                        },
                        onHandwriting = { callback ->
                            handwritingReturn = callback
                            handwritingReturnScreen = AppScreen.Board
                            screen = AppScreen.Handwriting
                        },
                        contentPadding = PaddingValues(0.dp),
                    )
                    AppScreen.CardDetail -> {
                        val todo = selectedTodo
                        if (todo == null) {
                            screen = AppScreen.Todos
                        } else {
                            CardDetailScreen(
                                todo = todo,
                                decks = run {
                                    val names = state.todoLists.associate { it.entityId to it.name }
                                    state.settings.deckEntityIds().map { id ->
                                        id to names.getOrElse(id) { id }
                                    }
                                },
                                onHome = { screen = AppScreen.Home },
                                onBack = { screen = AppScreen.Board },
                                onSave = { updated ->
                                    viewModel.update(updated)
                                    selectedTodoId = updated.id
                                },
                                onToggle = {
                                    viewModel.toggle(todo)
                                },
                                onDelete = {
                                    viewModel.delete(todo)
                                    screen = AppScreen.Board
                                },
                                onMove = { dest ->
                                    viewModel.move(todo, dest)
                                },
                                onHandwriting = { callback ->
                                    handwritingReturn = callback
                                    handwritingReturnScreen = AppScreen.CardDetail
                                    screen = AppScreen.Handwriting
                                },
                            )
                        }
                    }
                    AppScreen.Handwriting -> HandwritingScreen(
                        ocrEngine = ocrEngine,
                        onHome = { screen = AppScreen.Home },
                        onBack = { screen = handwritingReturnScreen },
                        onText = {
                            handwritingReturn(it)
                            screen = handwritingReturnScreen
                        },
                    )
                    AppScreen.Settings -> SettingsScreen(
                        settings = state.settings,
                        onHome = { screen = AppScreen.Home },
                        onSave = { url, entity, token, dashboardPath, boardIds ->
                            viewModel.saveSettings(url, entity, token, dashboardPath, boardIds)
                            screen = AppScreen.Home
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
fun HomeButton(onHome: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onHome,
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) { Text("Home") }
}

@Composable
fun ScreenHeader(
    title: String,
    onHome: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HomeButton(onHome)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
    }
}
