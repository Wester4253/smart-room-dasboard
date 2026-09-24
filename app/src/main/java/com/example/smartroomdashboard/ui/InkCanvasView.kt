package com.example.smartroomdashboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.example.smartroomdashboard.ocr.OcrPoint
import com.example.smartroomdashboard.ocr.OcrStroke

/**
 * Draws every contact the Boox sends. The Note Air2 Plus capacitive pen reports as a finger,
 * while an EMR pen reports as a stylus, so this view accepts every tool and source.
 */
@SuppressLint("ClickableViewAccessibility")
class InkCanvasView(context: Context) : View(context) {
    var onChanged: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val strokeWidth = 8f * density
    private val inkPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = this@InkCanvasView.strokeWidth
        isAntiAlias = false
    }
    private val hintPaint = Paint().apply {
        color = Color.BLACK
        textSize = 18f * density
        isAntiAlias = false
    }
    private val finished = mutableListOf<DrawnStroke>()
    private var active: DrawnStroke? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastTimestamp = 0L

    init {
        setBackgroundColor(Color.WHITE)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isLongClickable = false
        isHapticFeedbackEnabled = false
        isFocusable = true
        isFocusableInTouchMode = true
        applyBooxFastRefresh()
    }

    fun clear() {
        finished.clear()
        active = null
        activePointerId = MotionEvent.INVALID_POINTER_ID
        invalidate()
        onChanged?.invoke()
    }

    fun undo() {
        if (finished.isNotEmpty()) finished.removeAt(finished.lastIndex)
        invalidate()
        onChanged?.invoke()
    }

    fun hasInk(): Boolean = finished.isNotEmpty()

    fun toOcrStrokes(): List<OcrStroke> = finished.map { stroke ->
        val points = stroke.points.toMutableList()
        if (points.size == 1) {
            val point = points.first()
            points += point.copy(x = point.x + 1f, timestampMillis = point.timestampMillis + 8)
        }
        OcrStroke(points)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    val index = event.actionIndex
                    activePointerId = event.getPointerId(index)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    beginStroke(event, index)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val index = event.findPointerIndex(activePointerId)
                if (index >= 0) appendHistory(event, index)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val index = event.findPointerIndex(activePointerId)
                    if (index >= 0) appendHistory(event, index)
                    active?.let { stroke ->
                        if (stroke.points.isNotEmpty()) finished += stroke
                    }
                    active = null
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onChanged?.invoke()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                active?.let { stroke ->
                    if (stroke.points.isNotEmpty()) finished += stroke
                }
                active = null
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                onChanged?.invoke()
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        canvas.drawRect(1f, 1f, width - 1f, height - 1f, inkPaint)
        if (finished.isEmpty() && active == null) {
            canvas.drawText("Write here with the pen or a finger", 24f * density, height / 2f, hintPaint)
        }
        finished.forEach { canvas.drawPath(it.path, inkPaint) }
        active?.let { canvas.drawPath(it.path, inkPaint) }
    }

    private fun beginStroke(event: MotionEvent, index: Int) {
        val stroke = DrawnStroke()
        addPoint(stroke, event.getX(index), event.getY(index), event.eventTime, move = false)
        active = stroke
    }

    private fun appendHistory(event: MotionEvent, index: Int) {
        val stroke = active ?: DrawnStroke().also { active = it }
        for (history in 0 until event.getHistorySize()) {
            addPoint(
                stroke,
                event.getHistoricalX(index, history),
                event.getHistoricalY(index, history),
                event.getHistoricalEventTime(history),
                move = true,
            )
        }
        addPoint(stroke, event.getX(index), event.getY(index), event.eventTime, move = true)
    }

    private fun addPoint(stroke: DrawnStroke, x: Float, y: Float, eventTime: Long, move: Boolean) {
        if (!x.isFinite() || !y.isFinite()) return
        val timestamp = monotonic(eventTime)
        if (!move || stroke.points.isEmpty()) {
            stroke.path.moveTo(x, y)
        } else {
            stroke.path.lineTo(x, y)
        }
        stroke.points += OcrPoint(x, y, timestamp)
    }

    private fun monotonic(eventTime: Long): Long {
        val next = if (eventTime > lastTimestamp) eventTime else lastTimestamp + 8
        lastTimestamp = next
        return next
    }

    private class DrawnStroke(
        val path: Path = Path(),
        val points: MutableList<OcrPoint> = mutableListOf(),
    )
}

private fun View.applyBooxFastRefresh() {
    runCatching {
        val updateMode = View::class.java.methods.firstOrNull {
            it.name == "setUpdateMode" && it.parameterTypes.size == 1
        }
        updateMode?.invoke(this, 1)
    }
    runCatching {
        val modeClass = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode")
        val du = modeClass.enumConstants?.firstOrNull { it.toString() == "DU" } ?: return@runCatching
        val controller = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
        controller.methods.firstOrNull { method ->
            method.name == "applyTransientUpdate" && method.parameterTypes.size == 1
        }?.invoke(null, du)
    }
}
