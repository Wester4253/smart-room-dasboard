package com.example.smartroomdashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smartroomdashboard.domain.Todo
import com.example.smartroomdashboard.domain.deckEntityIds

/**
 * Trello-style board: every configured deck gets its own "To do" / "Done" pair of
 * columns. Decks come from `AppSettings.deckEntityIds()`, so a todo whose
 * `listEntityId` is no longer configured is intentionally not shown here.
 */
@Composable
fun KanbanBoardScreen(
    state: TodoUiState,
    onHome: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (Todo) -> Unit,
    onAdd: (String, String, String) -> Unit,
    onHandwriting: ((String) -> Unit) -> Unit,
    contentPadding: PaddingValues,
) {
    val names = state.todoLists.associate { it.entityId to it.name }
    val decks = state.settings.deckEntityIds()

    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = if (decks.size > 1) "Boards" else "Home Assistant todo board",
            onHome = onHome,
            trailing = {
                EinkOutlinedButton(
                    onClick = onRefresh,
                    enabled = !state.isBusy,
                    height = 52.dp,
                ) { Text(if (state.isBusy) "…" else "Refresh") }
            },
        )
        if (state.isBusy) {
            EinkBusyBar("Talking to Home Assistant…")
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            decks.forEach { deck ->
                val deckTodos = state.todos.filter { it.listEntityId == deck }
                item(key = "$deck-open") {
                    KanbanColumn(
                        title = names[deck] ?: deck,
                        todos = deckTodos.filterNot { it.completed },
                        onOpen = onOpen,
                        onAdd = { title, description -> onAdd(title, description, deck) },
                        onHandwriting = { onHandwriting { text -> onAdd(text, "", deck) } },
                    )
                }
                item(key = "$deck-done") {
                    KanbanColumn(
                        title = "Done",
                        todos = deckTodos.filter { it.completed },
                        onOpen = onOpen,
                        onAdd = null,
                        onHandwriting = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun KanbanColumn(
    title: String,
    todos: List<Todo>,
    onOpen: (Todo) -> Unit,
    onAdd: ((String, String) -> Unit)?,
    onHandwriting: (() -> Unit)?,
) {
    EinkCard(
        // 0.62 rather than 0.78: at the larger text sizes a column used to clip
        // its own card titles, and two columns plus a gap now fit on a 10.3"
        // panel without horizontal scrolling for the common two-deck case.
        modifier = Modifier.fillMaxWidth(0.62f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("$title (${todos.size})", style = MaterialTheme.typography.titleMedium)
            if (todos.isEmpty()) {
                Text(
                    "No cards",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            todos.forEach { todo ->
                EinkOutlinedButton(
                    onClick = { onOpen(todo) },
                    modifier = Modifier.fillMaxWidth(),
                    height = 52.dp,
                ) {
                    Text(
                        todo.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onAdd != null) {
                // `rememberSaveable` keyed on the deck: these drafts used to be
                // discarded every time the board was re-entered.
                var draft by rememberSaveable(title) { mutableStateOf("") }
                var notes by rememberSaveable(title) { mutableStateOf("") }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New card") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Notes (optional)") },
                    minLines = 2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onHandwriting != null) {
                        EinkOutlinedButton(
                            onClick = onHandwriting,
                            modifier = Modifier.weight(1f),
                        ) { Text("Write") }
                    }
                    EinkButton(
                        onClick = {
                            onAdd(draft.trim(), notes.trim())
                            draft = ""
                            notes = ""
                        },
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Add card") }
                }
            }
        }
    }
}
