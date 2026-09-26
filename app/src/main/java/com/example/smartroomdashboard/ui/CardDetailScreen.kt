package com.example.smartroomdashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smartroomdashboard.domain.Todo

/**
 * Full-detail editor for a single card. This is where editing, completing, moving
 * between decks and deleting live; the list and board screens only navigate here.
 *
 * `decks` is `entityId to display name`; the current list is preselected so the
 * move buttons never offer a no-op move.
 */
@Composable
fun CardDetailScreen(
    todo: Todo,
    decks: List<Pair<String, String>>,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onSave: (Todo) -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onMove: (String) -> Unit,
    onHandwriting: ((String) -> Unit) -> Unit,
    onMessage: () -> Unit = {},
) {
    // `rememberSaveable` keyed on the id: the old `remember(todo.id)` was thrown
    // away every time this composable left and re-entered composition, which is
    // exactly what happens on the handwriting round trip. Unsaved title and due
    // edits were silently lost.
    var title by rememberSaveable(todo.id) { mutableStateOf(todo.title) }
    var description by rememberSaveable(todo.id) { mutableStateOf(todo.description) }
    var dueDate by rememberSaveable(todo.id) { mutableStateOf(todo.dueDate.orEmpty()) }
    var confirmDelete by rememberSaveable(todo.id) { mutableStateOf(false) }

    val dirty = title != todo.title ||
        description != todo.description ||
        dueDate != todo.dueDate.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = if (todo.completed) "Completed card" else "Card",
            onHome = onHome,
            trailing = {
                EinkOutlinedButton(onClick = onBack) { Text("Back") }
            },
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Task") },
                singleLine = true,
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Notes (markdown)") },
                minLines = 3,
            )
            EinkOutlinedButton(
                onClick = {
                    // The lambda below closes over this composition's `description`,
                    // so the recognised text lands in the state that survives the
                    // round trip.
                    onHandwriting { recognised -> description = recognised }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.TouchApp, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Write notes")
            }
            if (description.isNotBlank()) {
                EinkCard(modifier = Modifier.fillMaxWidth()) {
                    MarkdownText(
                        markdown = description,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            OutlinedTextField(
                value = dueDate,
                onValueChange = { dueDate = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Due date (optional)") },
                supportingText = { Text("Passed through to Home Assistant unchanged.") },
                singleLine = true,
            )
            if (decks.size > 1) {
                EinkSectionHeader("Move to list")
                decks.forEach { (entityId, name) ->
                    val current = entityId == todo.listEntityId
                    EinkOutlinedButton(
                        onClick = { onMove(entityId) },
                        enabled = !current,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (current) "$name (current)" else name)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = todo.completed, onCheckedChange = { onToggle() })
                Text(if (todo.completed) "Done" else "Open")
            }
            EinkOutlinedButton(
                onClick = {
                    if (confirmDelete) {
                        confirmDelete = false
                        onDelete()
                    } else {
                        confirmDelete = true
                        onMessage()
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (confirmDelete) "Tap again" else "Delete") }
            EinkButton(
                onClick = {
                    onSave(
                        todo.copy(
                            title = title.trim(),
                            description = description.trim(),
                            dueDate = dueDate.trim().ifBlank { null },
                        ),
                    )
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text(if (dirty) "Save*" else "Save") }
        }
    }
}
