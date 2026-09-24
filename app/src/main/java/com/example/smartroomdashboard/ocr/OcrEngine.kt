package com.example.smartroomdashboard.ocr

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import kotlinx.coroutines.tasks.await

sealed interface OcrResult {
    data class Text(val value: String) : OcrResult
    data class Unavailable(val reason: String) : OcrResult
}

data class OcrPoint(
    val x: Float,
    val y: Float,
    val timestampMillis: Long,
)

data class OcrStroke(val points: List<OcrPoint>)

data class OcrInput(
    val strokes: List<OcrStroke>,
    val width: Float,
    val height: Float,
    val preContext: String = "",
)

interface OcrEngine {
    suspend fun recognize(input: OcrInput): OcrResult
}

class UnavailableOcrEngine : OcrEngine {
    override suspend fun recognize(input: OcrInput): OcrResult =
        OcrResult.Unavailable("Handwriting recognition is not configured; enter the text manually.")
}

class MlKitDigitalInkOcrEngine(
    private val languageTag: String = "en-US",
) : OcrEngine {
    private val model: DigitalInkRecognitionModel by lazy {
        val identifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
            ?: error("No Digital Ink model is available for $languageTag")
        DigitalInkRecognitionModel.builder(identifier).build()
    }
    private val recognizer: DigitalInkRecognizer by lazy {
        DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).build(),
        )
    }

    override suspend fun recognize(input: OcrInput): OcrResult {
        return try {
            val modelManager = RemoteModelManager.getInstance()
            if (!modelManager.isModelDownloaded(model).await()) {
                modelManager.download(
                    model,
                    DownloadConditions.Builder().build(),
                ).await()
            }

            val inkBuilder = Ink.builder()
            input.strokes.forEach { sourceStroke ->
                if (sourceStroke.points.isEmpty()) return@forEach
                val strokeBuilder = Ink.Stroke.builder()
                sourceStroke.points.forEach { point ->
                    strokeBuilder.addPoint(
                        Ink.Point.create(point.x, point.y, point.timestampMillis),
                    )
                }
                inkBuilder.addStroke(strokeBuilder.build())
            }

            val ink = inkBuilder.build()
            val context = RecognitionContext.builder()
                .setWritingArea(WritingArea(input.width, input.height))
                .setPreContext(input.preContext.takeLast(20))
                .build()
            val result = recognizer.recognize(ink, context).await()
            OcrResult.Text(result.candidates.firstOrNull()?.text.orEmpty())
        } catch (error: Exception) {
            OcrResult.Unavailable(
                error.message?.takeIf { it.isNotBlank() }
                    ?: "Handwriting recognition failed. Check the network and try again.",
            )
        }
    }
}
