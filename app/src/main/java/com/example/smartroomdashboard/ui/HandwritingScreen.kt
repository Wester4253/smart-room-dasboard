package com.example.smartroomdashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.smartroomdashboard.ocr.OcrEngine
import com.example.smartroomdashboard.ocr.OcrInput
import com.example.smartroomdashboard.ocr.OcrResult
import kotlinx.coroutines.launch

@Composable
fun HandwritingScreen(
    ocrEngine: OcrEngine,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var inkView by remember { mutableStateOf<InkCanvasView?>(null) }
    var inkRevision by remember { mutableIntStateOf(0) }
    var manualText by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("The Boox touch pen draws the same way a finger does.") }
    var recognizing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hasInk = inkRevision >= 0 && inkView?.hasInk() == true

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Write one task, then convert it. Review the text before adding it.")
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context ->
                InkCanvasView(context).also { view ->
                    view.onChanged = { inkRevision += 1 }
                    inkView = view
                }
            },
            update = { view ->
                view.onChanged = { inkRevision += 1 }
                if (inkView !== view) inkView = view
            },
        )
        Text(notice)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EinkOutlinedButton(
                onClick = { inkView?.clear() },
                modifier = Modifier.weight(1f),
            ) { Text("Clear") }
            EinkOutlinedButton(
                onClick = { inkView?.undo() },
                enabled = hasInk,
                modifier = Modifier.weight(1f),
            ) { Text("Undo") }
            EinkButton(
                onClick = {
                    val view = inkView ?: return@EinkButton
                    recognizing = true
                    notice = "Reading handwriting…"
                    scope.launch {
                        when (
                            val result = ocrEngine.recognize(
                                OcrInput(
                                    strokes = view.toOcrStrokes(),
                                    width = view.width.toFloat().coerceAtLeast(1f),
                                    height = view.height.toFloat().coerceAtLeast(1f),
                                ),
                            )
                        ) {
                            is OcrResult.Text -> {
                                recognizing = false
                                if (result.value.isNotBlank()) {
                                    manualText = result.value.trim()
                                    notice = "Review the text, then add the task."
                                } else {
                                    notice = "No text found. Type the task below."
                                }
                            }
                            is OcrResult.Unavailable -> {
                                recognizing = false
                                notice = result.reason
                            }
                        }
                    }
                },
                enabled = hasInk && !recognizing,
                modifier = Modifier.weight(1.4f),
            ) { Text(if (recognizing) "Reading…" else "Convert") }
        }
        OutlinedTextField(
            value = manualText,
            onValueChange = { manualText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Task text") },
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EinkOutlinedButton(
                onClick = onHome,
                modifier = Modifier.weight(1f),
            ) { Text("Home") }
            EinkOutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
            ) { Text("Back") }
            EinkButton(
                onClick = { onText(manualText.trim()) },
                enabled = manualText.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("Use text") }
        }
    }
}
