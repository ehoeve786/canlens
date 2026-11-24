package com.ehoeve.canpilot

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import com.ehoeve.canpilot.core.FrameStore
import kotlin.math.abs

/** The time range (seconds) the charts of one message show; they zoom and pan together. */
class ChartView(private val first: Float, private val last: Float) {
    var start by mutableFloatStateOf(first); private set
    var end by mutableFloatStateOf(maxOf(last, first + MIN_SPAN)); private set

    fun show(from: Float, to: Float) { start = from; end = maxOf(to, from + MIN_SPAN) }

    /** Zooms by [zoom] around [focus] and pans by [pan]; both are fractions of the chart's width. */
    fun transform(focus: Float, pan: Float, zoom: Float) {
        val span = end - start
        val newSpan = (span / zoom).coerceIn(MIN_SPAN, MAX_SPAN)
        val newStart = start + focus * span - focus * newSpan - pan * newSpan
        start = newStart
        end = newStart + newSpan
    }

    companion object {
        const val MIN_SPAN = 0.02f
        const val MAX_SPAN = 200_000f
    }
}

/** First index in times[0 until size] with a value >= [t]. */
private fun lowerBound(times: FloatArray, size: Int, t: Float): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (times[mid] < t) low = mid + 1 else high = mid
    }
    return low
}

/**
 * One signal over time, drawn as steps (a CAN signal holds its value until the next frame). Pinch to zoom, drag
 * sideways to pan, tap to move the cursor. Vertical drags are left to the list the chart sits in.
 */
@Composable
fun Chart(
    series: FrameStore.Series?, title: String, color: Color, view: ChartView, cursor: Float, markA: Float?, markB: Float?,
    onCursor: (Float) -> Unit, onMoved: () -> Unit,
) {
    val text = remember { Paint().apply { this.color = android.graphics.Color.WHITE; isAntiAlias = true } }
    Canvas(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).height(140.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF151A1E))
            .pointerInput(view) { detectTapGestures { onCursor(view.start + it.x / size.width * (view.end - view.start)) } }
            .pointerInput(view) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var movedX = 0f
                    var movedY = 0f
                    var mine: Boolean? = null   // null until the drag's direction is clear
                    do {
                        val event = awaitPointerEvent()
                        val pan = event.calculatePan()
                        movedX += pan.x; movedY += pan.y
                        if (mine == null) {
                            if (event.changes.count { it.pressed } >= 2) mine = true
                            else if (maxOf(abs(movedX), abs(movedY)) > viewConfiguration.touchSlop) mine = abs(movedX) > abs(movedY)
                        }
                        val centroid = event.calculateCentroid(useCurrent = false)
                        if (mine == true && centroid != Offset.Unspecified) {
                            view.transform(centroid.x / size.width, pan.x / size.width, event.calculateZoom())
                            onMoved()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (mine != false && event.changes.any { it.pressed })
                }
            }
    ) {
        val width = size.width
        val height = size.height
        val start = view.start
        val span = view.end - start
        fun x(t: Float) = (t - start) / span * width
        text.textSize = 11.dp.toPx()
        if (markA != null && markB != null) {
            val left = x(minOf(markA, markB)).coerceIn(0f, width)
            drawRect(Color(0x332E7D32), Offset(left, 0f), Size(x(maxOf(markA, markB)).coerceIn(0f, width) - left, height))
        }
        val n = series?.size ?: 0
        if (series != null && n > 0) {
            val times = series.times
            val values = series.values
            // one sample beyond each edge so the line runs to the borders
            val from = (lowerBound(times, n, start) - 1).coerceAtLeast(0)
            val to = (lowerBound(times, n, view.end) + 1).coerceAtMost(n)
            var low = Float.MAX_VALUE
            var high = -Float.MAX_VALUE
            for (i in from until to) { val v = values[i]; if (v < low) low = v; if (v > high) high = v }
            if (from < to) {
                if (high - low < 1e-9f) { low -= 1f; high += 1f }
                val pad = 14.dp.toPx()
                fun y(v: Float) = height - pad - (v - low) / (high - low) * (height - 2 * pad)
                if (to - from <= width * 2) {
                    val path = Path()
                    path.moveTo(x(times[from]), y(values[from]))
                    for (i in from + 1 until to) { val px = x(times[i]); path.lineTo(px, y(values[i - 1])); path.lineTo(px, y(values[i])) }
                    path.lineTo(minOf(width, x(view.end)), y(values[to - 1]))
                    drawPath(path, color, style = Stroke(1.5.dp.toPx()))
                } else {
                    // more samples than pixels: one vertical line per pixel column, from its lowest to its highest value
                    var column = Int.MIN_VALUE
                    var columnLow = 0f
                    var columnHigh = 0f
                    for (i in from until to) {
                        val c = x(times[i]).toInt()
                        val v = values[i]
                        if (c != column) {
                            if (column != Int.MIN_VALUE) drawLine(color, Offset(column.toFloat(), y(columnLow)), Offset(column.toFloat(), y(columnHigh) - 1f))
                            column = c
                            // start from the previous value so neighbouring columns join up
                            columnLow = if (i > from) minOf(v, values[i - 1]) else v
                            columnHigh = if (i > from) maxOf(v, values[i - 1]) else v
                        } else {
                            if (v < columnLow) columnLow = v
                            if (v > columnHigh) columnHigh = v
                        }
                    }
                    if (column != Int.MIN_VALUE) drawLine(color, Offset(column.toFloat(), y(columnLow)), Offset(column.toFloat(), y(columnHigh) - 1f))
                }
                val canvas = drawContext.canvas.nativeCanvas
                text.textAlign = Paint.Align.RIGHT
                canvas.drawText(number(high), width - 12f, text.textSize + 6f, text)
                canvas.drawText(number(low), width - 12f, height - 8f, text)
                // the value under the cursor: the last sample at or before it
                val at = lowerBound(times, n, cursor + 1e-6f) - 1
                if (at >= 0) canvas.drawText(number(values[at]), width - 12f, height / 2, text)
            }
        }
        text.textAlign = Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(if (n == 0) "$title: no data" else title, 12f, text.textSize + 6f, text)
        val cursorX = x(cursor)
        if (cursorX in 0f..width) drawLine(Color.White, Offset(cursorX, 0f), Offset(cursorX, height))
    }
}

private fun number(v: Float) = if (v == Math.rint(v.toDouble()).toFloat() && abs(v) < 1e7f) v.toInt().toString() else "%.3f".format(v)
