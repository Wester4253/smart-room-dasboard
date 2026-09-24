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
    onRefresh: () -> Unit,
    onAdd: (String) -> Unit,
    onUpdate: (Todo) -> Unit,
    onDelete: (Todo) -> Unit,
    onToggle: (Todo) -> Unit,
    onMessageShown: () -> Unit,
    onHandwriting: (((String) -> Unit)) -> Unit,
    contentPadding: PaddingValues,
) {
    var editor by remember { mutableStateOf<Todo?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${state.todos.count { !it.completed }} open · ${state.todos.size} total")
            OutlinedButton(onClick = onRefresh, enabled = !state.isBusy) {
                Text(if (state.isBusy) "Refreshing" else "Refresh")
            }
        }
        if (state.isBusy) Text("Loading tasks from Home Assistant…")
        if (state.todos.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(48.dp))
                Text("No tasks yet")
                Text("Add a task or write one by hand.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.todos, key = { it.id }) { todo ->
                    TodoRow(todo, onToggle, { editor = todo }, onDelete)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { showAdd = true },
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text("Add task") }
            OutlinedButton(
                onClick = { onHandwriting { handwritten -> onAdd(handwritten) } },
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text("Write task") }
        }
    }

    if (showAdd) {
        TodoEditorDialog(
            title = "Add task",
            initial = "",
            onDismiss = { showAdd = false },
            onSave = {
                onAdd(it)
                showAdd = false
            },
            onHandwriting = {
                showAdd = false
                onHandwriting { handwritten -> onAdd(handwritten) }
            },
        )
    }
    editor?.let { todo ->
        TodoEditorDialog(
            title = "Edit task",
            initial = todo.title,
            onDismiss = { editor = null },
            onSave = {
                onUpdate(todo.copy(title = it))
                editor = null
            },
        )
    }
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = onMessageShown,
            title = { Text("Notice") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onMessageShown) { Text("OK") } },
        )
    }
}

@Composable
private fun TodoRow(
    todo: Todo,
    onToggle: (Todo) -> Unit,
    onEdit: () -> Unit,
    onDelete: (Todo) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().border(2.dp, Color.Black, RoundedCornerShape(4.dp)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = todo.completed, onCheckedChange = { onToggle(todo) })
            Text(
                text = todo.title,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                textDecoration = if (todo.completed) TextDecoration.LineThrough else null,
            )
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
            IconButton(onClick = { onDelete(todo) }) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun TodoEditorDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onHandwriting: (() -> Unit)? = null,
) {
    var text by remember(initial) { mutableStateOf(initial) }
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
            Button(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
