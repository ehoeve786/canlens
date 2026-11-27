package com.ehoeve.canlens

import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.ehoeve.canlens.core.Analysis
import com.ehoeve.canlens.core.FrameStore
import com.ehoeve.canlens.core.Signal
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
        var runs = 0
        for (i in 0 until series.size) {
            if (i == 0 || series.values[i] != series.values[i - 1]) runs++
            distinct += series.values[i]; if (distinct.size > MAX_STATES) break
        }
        // few values that change nearly every frame (a counter, a checksum) read better as a line
        if (series.size == 0 || distinct.size > MAX_STATES || runs > maxOf(MAX_STATES, series.size / 4)) {
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

/** The first [MAX_CHANGES] changes in order, and how many there are in all. */
private class Changes(val first: List<Change>, val total: Int)

/** How many of the sorted [times] are below [t], or at most [t] with [orEqual]. */
private fun countBelow(times: FloatArray, t: Float, orEqual: Boolean): Int {
    var low = 0
    var high = times.size
    while (low < high) { val mid = (low + high) ushr 1; if (times[mid] < t || orEqual && times[mid] == t) low = mid + 1 else high = mid }
    return low
}

/** All plotted signals on one time axis: states as labelled strips, measurements as lines, and the state changes in order. */
@Composable
fun PlotsScreen(model: AppModel) {
    val revision = model.revision
    val store = model.store
    val view = model.plot.view
    val live = model.live.source != LiveSource.NONE
    val following = model.trace.followLive && live
    // after composition, not during it: the view is state the composition reads
    val liveEnd = store.lastTime
    LaunchedEffect(following, liveEnd) { if (following) view.show(maxOf(seconds(liveEnd) - LIVE_WINDOW_SECONDS, seconds(store.firstTime)), seconds(liveEnd)) }
    val plots = model.plot.keys.toList()
    val dbcs = model.dbc.files.toList()
    val liveFrom = if (following) ticks(view.start) else Int.MIN_VALUE
    // following, only the window in view is decoded, at every refresh; otherwise the whole log, every 5 s at most while a stream runs
    val refresh = if (following || !live) revision else revision / 20
    val data by produceState(emptyList<PlotData>(), refresh, plots, dbcs, liveFrom) {
        value = withContext(Dispatchers.Default) {
            plots.mapIndexedNotNull { position, key ->
                val message = store.messages.firstOrNull { it.bus == key.bus && it.id == key.id } ?: return@mapIndexedNotNull null
                // a DBC signal, or one Decode suggested and the DBC doesn't have yet
                val saved = model.dbc.decoder(key.bus, key.id)?.let { d -> d.signals.firstOrNull { it.name == key.signal }?.let { d to it } }
                val (decoder, signal) = saved ?: model.plot.unsaved[key] ?: return@mapIndexedNotNull null
                PlotData(key, signal, decoder.name, store.series(message, decoder, signal, liveFrom), SIGNAL_COLORS[position % SIGNAL_COLORS.size])
            }
        }
    }
    var query by remember { mutableStateOf("") }
    val list = remember(query) { model.plotList(query) }
    val matches = remember(query, revision, dbcs, plots) { if (list != null) emptyList() else model.signalsMatching(query, 40) }
    val a = model.trace.markA
    val b = model.trace.markB
    val marked = a != null && b != null
    val changes by produceState(Changes(emptyList(), 0), data, a, b) {
        val from = if (a != null && b != null) seconds(minOf(a, b)) else -Float.MAX_VALUE
        val to = if (a != null && b != null) seconds(maxOf(a, b)) else Float.MAX_VALUE
        val plotted = data
        value = withContext(Dispatchers.Default) {
            // of each state only its first MAX_CHANGES in the stretch: together they hold the first MAX_CHANGES of all
            val found = ArrayList<Change>()
            var total = 0
            for (plot in plotted) {
                val labels = plot.stateLabels ?: continue
                val first = countBelow(plot.runStarts, from, false).coerceAtLeast(1)
                val last = countBelow(plot.runStarts, to, true)
                if (first >= last) continue
                total += last - first
                for (i in first until minOf(last, first + MAX_CHANGES)) found += Change(plot.runStarts[i], plot, labels[plot.runState[i - 1]], labels[plot.runState[i]])
            }
            found.sortBy { it.time }
            Changes(if (found.size > MAX_CHANGES) found.subList(0, MAX_CHANGES).toList() else found, total)
        }
    }
    val cursor = seconds(model.trace.cursorNow)
    val markers = model.trace.markers.map { seconds(it.time) }
    val moveCursor: (Float) -> Unit = { model.trace.cursor = ticks(it); model.trace.followLive = false }
    val moved = { model.trace.followLive = false }
    val lines = remember(data) { data.filter { it.stateLabels == null }.map { it.series to it.color } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plots", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    if (plots.isNotEmpty()) SaveMenu(
                        listOf(SaveEntry("Save the signals as CSV", "canlens-signals.csv") { model.exportSignals(it, false) }) +
                            if (marked) listOf(SaveEntry("A–B as CSV", "canlens-signals-a-b.csv") { model.exportSignals(it, true) }) else emptyList()
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = CircleShape,
                    placeholder = { Text("Add a signal by name") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = if (query.isEmpty()) null else ({ IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") } }),
                )
                if (list != null) Button(onClick = { model.applyPlotList(list); query = "" }, Modifier.padding(top = 6.dp)) {
                    Text("Plot ${list.first.size} signals" + if (list.second.isEmpty()) "" else ", mark ${list.second.size} moments")
                } else if (query.isNotBlank() && matches.isEmpty()) Hint("No signal or message matches. Signals come from the DBC files and Decode's suggestions.", Modifier.padding(top = 6.dp, start = 4.dp))
            }
            items(matches.size, key = { "match:${matches[it].first}" }) { index ->
                val (key, messageName) = matches[index]
                Surface(onClick = { model.plot(key) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
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
                    if (marked) AssistChip(onClick = { view.show(seconds(minOf(a, b)), seconds(maxOf(a, b))); moved() }, label = { Text("Zoom to A–B") })
                    FilterChip(selected = model.plot.overlay, onClick = { model.plot.overlay = !model.plot.overlay }, label = { Text("Overlay") })
                    if (model.plot.overlay) FilterChip(selected = model.plot.sharedScale, onClick = { model.plot.sharedScale = !model.plot.sharedScale }, label = { Text("One scale") })
                    AssistChip(onClick = { model.plot.keys.clear() }, label = { Text("Remove all") })
                }
            }
            // overlay: the measurements share one chart; each keeps its row below as the legend, with its value at the cursor
            if (model.plot.overlay && lines.isNotEmpty()) item {
                OverlayChart(lines, model.plot.sharedScale, view, cursor, a?.let { seconds(it) }, b?.let { seconds(it) }, moveCursor, moved, markers)
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
                                // over the marked stretch: lowest, highest, average, and how often it changed
                                if (marked && plot.stateLabels == null) remember(plot.series, a, b) { Analysis.stats(plot.series, seconds(minOf(a!!, b!!)), seconds(maxOf(a, b))) }?.let { (low, high, mean, changes) ->
                                    Hint("A–B: %s to %s, average %s, %d changes".format(number(low), number(high), number(mean), changes.toInt()))
                                }
                            }
                            Text(plot.labelAt(cursor), fontFamily = MONO, fontWeight = FontWeight.Bold, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { model.plot.keys.remove(plot.key) }) { Icon(Icons.Default.Close, "Remove ${plot.key.signal}", Modifier.size(18.dp)) }
                        }
                        if (plot.stateLabels != null || !model.plot.overlay) Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp)) {
                            if (plot.stateLabels != null) StateStrip(plot, view, cursor, a?.let { seconds(it) }, b?.let { seconds(it) }, markers, moveCursor, moved)
                            else Chart(plot.series, "", plot.color, view, cursor, a?.let { seconds(it) }, b?.let { seconds(it) }, moveCursor, moved, markers, labels = false, height = 96.dp)
                        }
                    }
                }
            }
            if (changes.first.isNotEmpty()) item {
                SectionTitle(if (marked) "Changes between A and B" else "Changes", Modifier.padding(start = 4.dp, top = 10.dp))
                Hint((if (changes.total > MAX_CHANGES) "The first $MAX_CHANGES of ${changes.total}. " else "") + "In order. Tap one to move the cursor there." +
                    if (marked) "" else " Set marks A and B to list only a stretch.", Modifier.padding(horizontal = 4.dp))
            }
            items(changes.first.size) { index ->
                val change = changes.first[index]
                Surface(onClick = { moveCursor(change.time) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(88.dp)) {
                            Text("%.2f s".format(change.time), fontFamily = MONO, style = MaterialTheme.typography.bodyMedium)
                            store.clock(ticks(change.time))?.let { Text(it, fontFamily = MONO, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
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

private fun number(v: Float) = if (v == Math.rint(v.toDouble()).toFloat() && kotlin.math.abs(v) < 1e7f) v.toInt().toString() else "%.3g".format(v)

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
        var drawn = 0f   // right end of the last block: a widened one-frame block isn't painted over by the next
        for (i in 0 until runs) {
            val right = if (i + 1 < runs) x(plot.runStarts[i + 1]) else width
            if (right <= 0f) continue
            val left = x(plot.runStarts[i])
            if (left >= width) break
            val from = maxOf(left, drawn)
            val to = right.coerceAtMost(width)
            if (from >= width) break
            if (to <= drawn) continue   // hidden under the widened block before it
            // at least a pixel, so a state held for one frame still shows when zoomed out
            drawRect(STATE_COLORS[plot.runState[i] % STATE_COLORS.size], Offset(from, 0f), Size(maxOf(to - from, 1f), height))
            drawn = from + maxOf(to - from, 1f)
            val label = labels[plot.runState[i]]
            if (text.measureText(label) + 12f < to - from) canvas.drawText(label, from + 6f, baseline, text)
        }
        for (mark in listOfNotNull(markA, markB)) { val markX = x(mark); if (markX in 0f..width) drawLine(Color(0xFF81C784), Offset(markX, 0f), Offset(markX, height), 2f) }
        for (marker in markers) { val markerX = x(marker); if (markerX in 0f..width) drawLine(MARKER, Offset(markerX, 0f), Offset(markerX, height)) }
        val cursorX = x(cursor)
        if (cursorX in 0f..width) drawLine(Color.White, Offset(cursorX, 0f), Offset(cursorX, height))
    }
}
