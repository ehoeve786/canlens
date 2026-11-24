package com.ehoeve.canpilot

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehoeve.canpilot.core.FrameStore
import com.ehoeve.canpilot.core.Signal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lowest value first: the first colour is for "0 / off / none". */
private val STATE_COLORS = listOf(0xFF37474F, 0xFF1565C0, 0xFF2E7D32, 0xFFEF6C00, 0xFF6A1B9A, 0xFFC62828, 0xFF00838F, 0xFF9E9D24).map { Color(it) }
private const val MAX_STATES = 8
private const val MAX_CHANGES = 300

/**
 * One plotted signal, ready to draw. A signal with at most [MAX_STATES] different values is a state: it is drawn as a
 * strip of labelled blocks and its changes are listed; [runStarts]/[runState] hold its runs of equal values.
 */
private class PlotData(val key: PlotKey, val signal: Signal, val messageName: String, val series: FrameStore.Series, val color: Color) {
    val stateLabels: List<String>?
    val runStarts: FloatArray
    val runState: IntArray

    init {
        val distinct = sortedSetOf<Float>()
        for (i in 0 until series.size) { distinct += series.values[i]; if (distinct.size > MAX_STATES) break }
        if (series.size == 0 || distinct.size > MAX_STATES) {
            stateLabels = null; runStarts = FloatArray(0); runState = IntArray(0)
        } else {
            val values = distinct.toList()
            stateLabels = values.map { label(it) }
            var starts = FloatArray(64)
            var states = IntArray(64)
            var n = 0
            for (i in 0 until series.size) {
                if (i > 0 && series.values[i] == series.values[i - 1]) continue
                if (n == starts.size) { starts = starts.copyOf(n * 2); states = states.copyOf(n * 2) }
                starts[n] = series.times[i]
                states[n++] = values.indexOf(series.values[i])
            }
            runStarts = starts.copyOf(n); runState = states.copyOf(n)
        }
    }

    /** The value's name from the DBC, else the number with the unit. */
    fun label(value: Float): String {
        if (signal.factor != 0.0) signal.valueNames[Math.round((value - signal.offset) / signal.factor)]?.let { return it }
        val number = if (value == Math.rint(value.toDouble()).toFloat() && Math.abs(value) < 1e7f) value.toInt().toString() else "%.2f".format(value)
        return if (signal.unit.isEmpty()) number else "$number ${signal.unit}"
    }

    /** The value at [seconds]: that of the last frame at or before it. */
    fun labelAt(seconds: Float): String {
        var low = 0
        var high = series.size
        while (low < high) { val mid = (low + high) ushr 1; if (series.times[mid] <= seconds) low = mid + 1 else high = mid }
        return if (low == 0) "–" else label(series.values[low - 1])
    }
}

private class Change(val time: Float, val plot: PlotData, val from: String, val to: String)

/** All plotted signals on one time axis: states as labelled strips, measurements as lines, and the state changes in order. */
@Composable
fun PlotsScreen(model: AppModel, onBack: () -> Unit) {
    val revision = model.revision
    val store = model.store
    val view = model.chartView
    val following = model.followLive && model.live != LiveSource.NONE
    if (following) view.show(maxOf(seconds(store.lastTime) - LIVE_WINDOW_SECONDS, seconds(store.firstTime)), seconds(store.lastTime))
    val plots = model.plots.toList()
    val dbcs = model.dbcs.toList()
    val liveFrom = if (following) ticks(view.start) else Int.MIN_VALUE
    val data by produceState(emptyList<PlotData>(), revision, plots, dbcs, liveFrom) {
        value = withContext(Dispatchers.Default) {
            plots.mapIndexedNotNull { position, key ->
                val message = store.messages.firstOrNull { it.bus == key.bus && it.id == key.id } ?: return@mapIndexedNotNull null
                val decoder = model.decoder(key.bus, key.id) ?: return@mapIndexedNotNull null
                val signal = decoder.signals.firstOrNull { it.name == key.signal } ?: return@mapIndexedNotNull null
                PlotData(key, signal, decoder.name, store.series(message, decoder, signal, liveFrom), SIGNAL_COLORS[position % SIGNAL_COLORS.size])
            }
        }
    }
    var query by remember { mutableStateOf("") }
    val matches = remember(query, revision, dbcs, plots) { model.signalsMatching(query, 40) }
    val a = model.markA
    val b = model.markB
    val marked = a != null && b != null
    val changes = remember(data, a, b) {
        val from = if (marked) seconds(minOf(a!!, b!!)) else -Float.MAX_VALUE
        val to = if (marked) seconds(maxOf(a!!, b!!)) else Float.MAX_VALUE
        val found = ArrayList<Change>()
        for (plot in data) {
            val labels = plot.stateLabels ?: continue
            for (i in 1 until plot.runStarts.size) {
                val time = plot.runStarts[i]
                if (time in from..to) found += Change(time, plot, labels[plot.runState[i - 1]], labels[plot.runState[i]])
            }
        }
        found.sortBy { it.time }
        found
    }
    val cursor = seconds(model.cursorNow)
    val markers = model.markers.map { seconds(it.time) }
    val moveCursor: (Float) -> Unit = { model.cursor = ticks(it); model.followLive = false }
    val moved = { model.followLive = false }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Plots", style = MaterialTheme.typography.titleMedium) },
            )
        },
        bottomBar = { TimeBar(model) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = CircleShape,
                    placeholder = { Text("Add a signal by name") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = if (query.isEmpty()) null else ({ IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") } }),
                )
                if (query.isNotBlank() && matches.isEmpty()) Hint("No decoded signal with that name. Signals come from the DBC files.", Modifier.padding(top = 6.dp, start = 4.dp))
            }
            items(matches.size, key = { "match:${matches[it].first}" }) { index ->
                val (key, messageName) = matches[index]
                Surface(onClick = { model.plots += key }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(key.signal, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Hint("bus ${key.bus} · ${hexId(key.id)} · $messageName")
                        }
                        Icon(Icons.Default.Add, "Plot ${key.signal}", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (plots.isEmpty()) item {
                Hint("Nothing plotted yet. Search for a signal above, or open a message and tick its signals.", Modifier.padding(4.dp))
            } else item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { view.show(seconds(store.firstTime), seconds(store.lastTime)); moved() }, label = { Text("Show everything") })
                    if (marked) AssistChip(onClick = { view.show(seconds(minOf(a!!, b!!)), seconds(maxOf(a!!, b!!))); moved() }, label = { Text("Zoom to A–B") })
                    AssistChip(onClick = { model.plots.clear() }, label = { Text("Remove all") })
                }
            }
            items(data.size, key = { "plot:${data[it].key}" }) { index ->
                val plot = data[index]
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(plot.color))
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(plot.key.signal, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Hint("bus ${plot.key.bus} · ${hexId(plot.key.id)} · ${plot.messageName}")
                            }
                            Text(plot.labelAt(cursor), fontFamily = MONO, fontWeight = FontWeight.Bold, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { model.plots.remove(plot.key) }) { Icon(Icons.Default.Close, "Remove ${plot.key.signal}", Modifier.size(18.dp)) }
                        }
                        Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp)) {
                            if (plot.stateLabels != null) StateStrip(plot, view, cursor, a?.let { seconds(it) }, b?.let { seconds(it) }, markers, moveCursor, moved)
                            else Chart(plot.series, "", plot.color, view, cursor, a?.let { seconds(it) }, b?.let { seconds(it) }, moveCursor, moved, markers, labels = false, height = 96.dp)
                        }
                    }
                }
            }
            if (changes.isNotEmpty()) item {
                SectionTitle(if (marked) "Changes between A and B" else "Changes", Modifier.padding(start = 4.dp, top = 10.dp))
                Hint((if (changes.size > MAX_CHANGES) "The first $MAX_CHANGES of ${changes.size}. " else "") + "In order. Tap one to move the cursor there." +
                    if (marked) "" else " Set marks A and B to list only a stretch.", Modifier.padding(horizontal = 4.dp))
            }
            items(minOf(changes.size, MAX_CHANGES)) { index ->
                val change = changes[index]
                Surface(onClick = { moveCursor(change.time) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("%.2f s".format(change.time), Modifier.width(88.dp), fontFamily = MONO, style = MaterialTheme.typography.bodyMedium)
                        Box(Modifier.size(10.dp).clip(CircleShape).background(change.plot.color))
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(change.plot.key.signal, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text("${change.from}  →  ${change.to}", fontFamily = MONO, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** A state over time as coloured blocks, each labelled with its value where the block is wide enough. Shares the plots' time range and gestures. */
@Composable
private fun StateStrip(plot: PlotData, view: ChartView, cursor: Float, markA: Float?, markB: Float?, markers: List<Float>, onCursor: (Float) -> Unit, onMoved: () -> Unit) {
    val text = remember { Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = true } }
    val labels = plot.stateLabels ?: return
    Canvas(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF151A1E)).timeGestures(view, onCursor, onMoved)) {
        val width = size.width
        val height = size.height
        val start = view.start
        val span = view.end - start
        fun x(t: Float) = (t - start) / span * width
        text.textSize = 12.dp.toPx()
        val baseline = height / 2 + text.textSize / 3
        val canvas = drawContext.canvas.nativeCanvas
        val runs = plot.runStarts.size
        for (i in 0 until runs) {
            val right = if (i + 1 < runs) x(plot.runStarts[i + 1]) else width
            if (right <= 0f) continue
            val left = x(plot.runStarts[i])
            if (left >= width) break
            val from = left.coerceAtLeast(0f)
            val to = right.coerceAtMost(width)
            // at least a pixel, so a state held for one frame still shows when zoomed out
            drawRect(STATE_COLORS[plot.runState[i] % STATE_COLORS.size], Offset(from, 0f), Size(maxOf(to - from, 1f), height))
            val label = labels[plot.runState[i]]
            if (text.measureText(label) + 12f < to - from) canvas.drawText(label, from + 6f, baseline, text)
        }
        for (mark in listOfNotNull(markA, markB)) { val markX = x(mark); if (markX in 0f..width) drawLine(Color(0xFF81C784), Offset(markX, 0f), Offset(markX, height), 2f) }
        for (marker in markers) { val markerX = x(marker); if (markerX in 0f..width) drawLine(MARKER, Offset(markerX, 0f), Offset(markerX, height)) }
        val cursorX = x(cursor)
        if (cursorX in 0f..width) drawLine(Color.White, Offset(cursorX, 0f), Offset(cursorX, height))
    }
}
