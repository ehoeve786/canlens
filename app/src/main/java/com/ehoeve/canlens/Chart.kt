package com.ehoeve.canlens

import android.graphics.Bitmap
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ehoeve.canlens.core.FrameStore
import com.ehoeve.canlens.core.Signal
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

private fun lowerBound(times: IntArray, size: Int, t: Int): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (times[mid] < t) low = mid + 1 else high = mid
    }
    return low
}

/** Pinch to zoom, drag sideways to pan, tap to move the cursor. Vertical drags are left to the list around it. */
fun Modifier.timeGestures(view: ChartView, onCursor: (Float) -> Unit, onMoved: () -> Unit): Modifier = this
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

/** The lowest and highest value of [series] inside the view, pulled apart when they are the same; null when nothing of it is in view. */
private fun bounds(series: FrameStore.Series, view: ChartView): ClosedFloatingPointRange<Float>? {
    val from = (lowerBound(series.times, series.size, view.start) - 1).coerceAtLeast(0)
    val to = (lowerBound(series.times, series.size, view.end) + 1).coerceAtMost(series.size)
    if (from >= to) return null
    var low = Float.MAX_VALUE
    var high = -Float.MAX_VALUE
    for (i in from until to) { val v = series.values[i]; if (v < low) low = v; if (v > high) high = v }
    return if (high - low < 1e-9f) low - 1f..high + 1f else low..high
}

/** Draws [series] as steps (a CAN signal holds its value until the next frame), with [low]..[high] filling the height. */
private fun DrawScope.drawSeries(series: FrameStore.Series, color: Color, view: ChartView, low: Float, high: Float) {
    val width = size.width
    val height = size.height
    val start = view.start
    val span = view.end - start
    val times = series.times
    val values = series.values
    // one sample beyond each edge so the line runs to the borders
    val from = (lowerBound(times, series.size, start) - 1).coerceAtLeast(0)
    val to = (lowerBound(times, series.size, view.end) + 1).coerceAtMost(series.size)
    if (from >= to) return
    val pad = 14.dp.toPx()
    fun x(t: Float) = (t - start) / span * width
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
                if (column != Int.MIN_VALUE) {
                    drawLine(color, Offset(column.toFloat(), y(columnLow)), Offset(column.toFloat(), y(columnHigh) - 1f))
                    // columns without a sample: the value holds until the next one, as in the steps above
                    if (c > column + 1) drawLine(color, Offset(column.toFloat(), y(values[i - 1])), Offset(c.toFloat(), y(values[i - 1])))
                }
                column = c
                // start from the previous value so neighbouring columns join up
                columnLow = if (i > from) minOf(v, values[i - 1]) else v
                columnHigh = if (i > from) maxOf(v, values[i - 1]) else v
            } else {
                if (v < columnLow) columnLow = v
                if (v > columnHigh) columnHigh = v
            }
        }
        if (column != Int.MIN_VALUE) {
            drawLine(color, Offset(column.toFloat(), y(columnLow)), Offset(column.toFloat(), y(columnHigh) - 1f))
            // and the last value on to the right edge
            val end = minOf(width, x(view.end))
            if (end > column + 1) drawLine(color, Offset(column.toFloat(), y(values[to - 1])), Offset(end, y(values[to - 1])))
        }
    }
}

/** The A–B window, behind the lines. */
private fun DrawScope.drawWindow(view: ChartView, markA: Float?, markB: Float?) {
    if (markA == null || markB == null) return
    fun x(t: Float) = ((t - view.start) / (view.end - view.start) * size.width).coerceIn(0f, size.width)
    val left = x(minOf(markA, markB))
    drawRect(Color(0x332E7D32), Offset(left, 0f), Size(x(maxOf(markA, markB)) - left, size.height))
}

/** Markers and the cursor, over the lines. */
private fun DrawScope.drawCursor(view: ChartView, cursor: Float, markers: List<Float>) {
    fun x(t: Float) = (t - view.start) / (view.end - view.start) * size.width
    for (marker in markers) { val markerX = x(marker); if (markerX in 0f..size.width) drawLine(MARKER, Offset(markerX, 0f), Offset(markerX, size.height)) }
    val cursorX = x(cursor)
    if (cursorX in 0f..size.width) drawLine(Color.White, Offset(cursorX, 0f), Offset(cursorX, size.height))
}

private fun Modifier.chart(height: Dp, view: ChartView, onCursor: (Float) -> Unit, onMoved: () -> Unit) =
    fillMaxWidth().padding(vertical = 4.dp).height(height).clip(RoundedCornerShape(14.dp)).background(Color(0xFF151A1E)).timeGestures(view, onCursor, onMoved)

/**
 * One signal over time. Pinch to zoom, drag sideways to pan, tap to move the cursor. Vertical drags are left to the
 * list the chart sits in.
 */
@Composable
fun Chart(
    series: FrameStore.Series?, title: String, color: Color, view: ChartView, cursor: Float, markA: Float?, markB: Float?,
    onCursor: (Float) -> Unit, onMoved: () -> Unit, markers: List<Float> = emptyList(), labels: Boolean = true, height: Dp = 140.dp,
) {
    val text = remember { Paint().apply { this.color = android.graphics.Color.WHITE; isAntiAlias = true } }
    Canvas(Modifier.chart(height, view, onCursor, onMoved)) {
        text.textSize = 11.dp.toPx()
        drawWindow(view, markA, markB)
        val n = series?.size ?: 0
        val range = series?.let { bounds(it, view) }
        val canvas = drawContext.canvas.nativeCanvas
        if (series != null && range != null) {
            drawSeries(series, color, view, range.start, range.endInclusive)
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(number(range.endInclusive), size.width - 12f, text.textSize + 6f, text)
            canvas.drawText(number(range.start), size.width - 12f, size.height - 8f, text)
            // the value under the cursor: the last sample at or before it
            val at = lowerBound(series.times, n, cursor + 1e-6f) - 1
            if (labels && at >= 0) canvas.drawText(number(series.values[at]), size.width - 12f, size.height / 2, text)
        }
        text.textAlign = Paint.Align.LEFT
        if (labels || n == 0) canvas.drawText(if (n == 0) "$title: no data".removePrefix(": ") else title, 12f, text.textSize + 6f, text)
        drawCursor(view, cursor, markers)
    }
}

/**
 * Several signals on one chart. With [sharedScale] they share one value axis, whose ends are written at the right;
 * without it each fills the height by itself, which compares shapes of signals with different units.
 */
@Composable
fun OverlayChart(
    series: List<Pair<FrameStore.Series, Color>>, sharedScale: Boolean, view: ChartView, cursor: Float, markA: Float?, markB: Float?,
    onCursor: (Float) -> Unit, onMoved: () -> Unit, markers: List<Float>, height: Dp = 240.dp,
) {
    val text = remember { Paint().apply { this.color = android.graphics.Color.WHITE; isAntiAlias = true; textAlign = Paint.Align.RIGHT } }
    Canvas(Modifier.chart(height, view, onCursor, onMoved)) {
        drawWindow(view, markA, markB)
        val ranges = series.map { bounds(it.first, view) }
        val low = ranges.minOfOrNull { it?.start ?: Float.MAX_VALUE } ?: 0f
        val high = ranges.maxOfOrNull { it?.endInclusive ?: -Float.MAX_VALUE } ?: 0f
        for ((index, entry) in series.withIndex()) {
            val own = ranges[index] ?: continue
            drawSeries(entry.first, entry.second, view, if (sharedScale) low else own.start, if (sharedScale) high else own.endInclusive)
        }
        if (sharedScale && low < high) {
            text.textSize = 11.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText(number(high), size.width - 12f, text.textSize + 6f, text)
            drawContext.canvas.nativeCanvas.drawText(number(low), size.width - 12f, size.height - 8f, text)
        }
        drawCursor(view, cursor, markers)
    }
}

private fun number(v: Float) = if (v == Math.rint(v.toDouble()).toFloat() && abs(v) < 1e7f) v.toInt().toString() else "%.4g".format(v)

private const val STRIP_COLUMNS = 320
private const val STRIP_FRAMES_PER_COLUMN = 512

/**
 * The payload of one message over time: a column per moment, a row per bit (byte 0 on top, its bit 7 first). Set bits
 * are lit, in the colour of the signal that owns them. Shares [view] and its gestures with the plots.
 */
@Composable
fun HistoryStrip(payloads: FrameStore.Payloads?, signals: List<Signal>, view: ChartView, cursor: Float, onCursor: (Float) -> Unit, onMoved: () -> Unit) {
    if (payloads == null || payloads.size == 0) return
    val rows = payloads.length * 8
    val start = view.start
    val end = view.end
    val image by produceState<ImageBitmap?>(null, payloads, signals, start, end) {
        value = withContext(Dispatchers.Default) {
            val pixels = IntArray(STRIP_COLUMNS * rows)
            val lit = IntArray(rows) { row ->
                val bit = row / 8 * 8 + 7 - row % 8
                val owner = signals.indexOfFirst { bit in it.bits }
                if (owner >= 0) SIGNAL_COLORS[owner % SIGNAL_COLORS.size].toArgb() else 0xFF90A4AE.toInt()
            }
            val length = payloads.length
            val bytes = IntArray(length)
            for (column in 0 until STRIP_COLUMNS) {
                // a bit is lit when it was set in any frame of the column's stretch of time
                val first = lowerBound(payloads.times, payloads.size, ticks(start + column.toFloat() / STRIP_COLUMNS * (end - start)))
                val last = lowerBound(payloads.times, payloads.size, ticks(start + (column + 1f) / STRIP_COLUMNS * (end - start)))
                java.util.Arrays.fill(bytes, 0)
                if (first < last) {
                    // zoomed far out, a column reads at most STRIP_FRAMES_PER_COLUMN of its frames, evenly spread
                    val stride = (last - first + STRIP_FRAMES_PER_COLUMN - 1) / STRIP_FRAMES_PER_COLUMN
                    for (frame in first until last step stride) for (b in 0 until length) bytes[b] = bytes[b] or payloads.data[frame * length + b].toInt()
                } else {
                    // none in it: the frame before holds
                    if (first == 0) continue
                    for (b in 0 until length) bytes[b] = payloads.data[(first - 1) * length + b].toInt()
                }
                for (row in 0 until rows) {
                    val set = bytes[row / 8] shr (7 - row % 8) and 1 == 1
                    pixels[row * STRIP_COLUMNS + column] = if (set) lit[row] else 0xFF1B2126.toInt()
                }
            }
            Bitmap.createBitmap(pixels, STRIP_COLUMNS, rows, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    Canvas(Modifier.fillMaxWidth().height((rows * 2).coerceIn(48, 128).dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF151A1E)).timeGestures(view, onCursor, onMoved)) {
        image?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None) }
        val cursorX = (cursor - start) / (end - start) * size.width
        if (cursorX in 0f..size.width) drawLine(Color.White, Offset(cursorX, 0f), Offset(cursorX, size.height))
    }
}
