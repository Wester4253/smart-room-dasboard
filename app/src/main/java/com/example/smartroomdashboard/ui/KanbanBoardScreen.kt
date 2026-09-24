package com.example.smartroomdashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smartroomdashboard.domain.Todo

@Composable
fun KanbanBoardScreen(
    todos: List<Todo>,
    isBusy: Boolean,
    onRefresh: () -> Unit,
    onToggle: (Todo) -> Unit,
    onAdd: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Home Assistant todo board", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onRefresh, enabled = !isBusy) {
                Text(if (isBusy) "Refreshing" else "Refresh")
            }
        }
        if (isBusy) Text("Loading tasks from Home Assistant…")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                KanbanColumn("To do", todos.filterNot { it.completed }, onToggle, onAdd)
            }
            item {
                KanbanColumn("Done", todos.filter { it.completed }, onToggle, null)
            }
        }
    }
}

@Composable
private fun KanbanColumn(
    title: String,
    todos: List<Todo>,
    onToggle: (Todo) -> Unit,
    onAdd: ((String) -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(0.78f).border(2.dp, Color.Black, RoundedCornerShape(4.dp)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("$title (${todos.size})")
            todos.forEach { todo ->
                OutlinedButton(
                    onClick = { onToggle(todo) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(todo.title)
                }
            }
            if (todos.isEmpty()) Text("No cards")
            if (onAdd != null) {
                var draft by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New card") },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        onAdd(draft.trim())
                        draft = ""
                    },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Add card")
                }
            }
        }
    }
}
