package com.example.smartroomdashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.smartroomdashboard.domain.Todo

@Composable
fun TodoListScreen(
    state: TodoUiState,
    onHome: () -> Unit,
    onRefresh: () -> Unit,
    onAdd: (String, String) -> Unit,
    onOpen: (Todo) -> Unit,
    onDelete: (Todo) -> Unit,
    onToggle: (Todo) -> Unit,
    onMessageShown: () -> Unit,
    onHandwriting: (((String) -> Unit)) -> Unit,
    contentPadding: PaddingValues,
) {
    var showAdd by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader(
            title = "${state.todos.count { !it.completed }} open · ${state.todos.size} total",
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
        if (state.todos.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
            ) {
                EinkEmptyState(
                    title = "No tasks yet",
                    subtitle = "Add a task or write one by hand.",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.todos, key = { it.id }) { todo ->
                    TodoRow(todo, onToggle, { onOpen(todo) }, onDelete)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EinkButton(
                onClick = { showAdd = true },
                modifier = Modifier.weight(1f),
            ) { Text("Add task") }
            EinkOutlinedButton(
                onClick = { onHandwriting { handwritten -> onAdd(handwritten, "") } },
                modifier = Modifier.weight(1f),
            ) { Text("Write task") }
        }
    }

    if (showAdd) {
        TodoEditorDialog(
            title = "Add task",
            initialTitle = "",
            initialDescription = "",
            onDismiss = { showAdd = false },
            onSave = { title, description ->
                onAdd(title, description)
                showAdd = false
            },
            onHandwriting = {
                showAdd = false
                onHandwriting { handwritten -> onAdd(handwritten, "") }
            },
        )
    }
    // `state.message` is rendered once, by SmartRoomApp. It used to be rendered
    // only here, so a failed mutation started from the board or the card detail
    // screen was silently dropped.
}

@Composable
private fun TodoRow(
    todo: Todo,
    onToggle: (Todo) -> Unit,
    onOpen: () -> Unit,
    onDelete: (Todo) -> Unit,
) {
    // Delete is irreversible and syncs to Home Assistant, so it asks first. The
    // card-detail screen uses a two-tap confirm; a dialog here matches the
    // existing `TodoEditorDialog` styling.
    var confirmDelete by remember(todo.id) { mutableStateOf(false) }

    EinkCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = todo.completed, onCheckedChange = { onToggle(todo) })
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = todo.title,
                    textDecoration = if (todo.completed) TextDecoration.LineThrough else null,
                )
                if (todo.dueDate?.isNotBlank() == true) {
                    Text(
                        text = "Due ${todo.dueDate}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onOpen) { Icon(Icons.Outlined.Edit, contentDescription = "Open") }
            IconButton(
                onClick = { if (confirmDelete) onDelete(todo) else confirmDelete = true },
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = if (confirmDelete) "Confirm delete" else "Delete",
                    tint = if (confirmDelete) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this task?") },
            text = { Text("\"${todo.title}\" will be removed from Home Assistant.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(todo)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun TodoEditorDialog(
    title: String,
    initialTitle: String,
    initialDescription: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onHandwriting: (() -> Unit)? = null,
) {
    var text by remember(initialTitle) { mutableStateOf(initialTitle) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Task") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Notes (optional)") },
                    minLines = 2,
                )
                onHandwriting?.let {
                    OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.TouchApp, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Write by hand")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(text, description) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
