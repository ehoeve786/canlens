package com.ehoeve.canpilot

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ehoeve.canpilot.core.BitSummary
import com.ehoeve.canpilot.core.FrameStore
import com.ehoeve.canpilot.core.LogReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The open data and what ties the parts together: loading logs, and resetting every part when other data comes.
 * The parts keep their own state: [dbc], [plot], [trace], [live], [signals].
 */
class AppModel(application: Application) : AndroidViewModel(application) {
    private val context: Context get() = getApplication()
    private val preferences = application.getSharedPreferences("canpilot", Context.MODE_PRIVATE)

    val store = FrameStore()
    /** Goes up whenever the store's content changed; composables read it to refresh. */
    var revision by mutableIntStateOf(0); private set
    var status by mutableStateOf("Open a log or start a live stream"); private set
    var busy by mutableStateOf(false); private set

    val plot = PlotState()
    val dbc = DbcState(application, preferences, viewModelScope, plot.keys) { status = it }
    val live = LiveState(application, preferences, viewModelScope, store, { status = it }, { reset(0, true, ChartView(0f, LIVE_WINDOW_SECONDS)) }) { revision++ }
    val trace = TraceState(store, preferences) { live.source != LiveSource.NONE }
    val signals = SignalState(store, viewModelScope, dbc, trace) { status = it }

    /** Another log to compare with, summed up bit by bit, and the same summary of the open data. */
    var other by mutableStateOf<BitSummary?>(null); private set
    var mine by mutableStateOf<BitSummary?>(null); private set
    var otherName by mutableStateOf(""); private set

    private fun reset(cursor: Int, follow: Boolean, view: ChartView) {
        trace.reset(cursor, follow)
        plot.view = view
        signals.reset()
        clearCompare()
    }

    fun openLogs(uris: List<Uri>) {
        if (uris.isEmpty() || busy) return
        live.stop()
        clearCompare()
        busy = true
        viewModelScope.launch {
            val problems = ArrayList<String>()
            val notes = ArrayList<Pair<Long, String>>()
            withContext(Dispatchers.IO) {
                store.clear()
                store.dropOldest = false
                // route segments in order: natural sort puts --2-- before --10--
                val files = uris.map { context.displayName(it) to it }.sortedWith { a, b -> naturalCompare(a.first, b.first) }
                for ((index, file) in files.withIndex()) {
                    withContext(Dispatchers.Main) { status = "Reading ${file.first} (${index + 1}/${files.size})" }
                    val before = store.frameCount
                    try {
                        context.openDecompressed(file.second).use { LogReader.read(it, store) { time, note -> notes += time to note } }
                        if (store.frameCount == before) problems += "${file.first}: no CAN frames"
                    } catch (e: Exception) {
                        problems += "${file.first}: ${e.message ?: e.javaClass.simpleName}"
                    } catch (e: OutOfMemoryError) {
                        problems += "${file.first}: out of memory"
                    }
                    if (store.truncated) { problems += "memory limit reached, the rest was not loaded"; break }
                }
            }
            reset(store.firstTime, false, ChartView(seconds(store.firstTime), seconds(store.lastTime)))
            if (store.frameCount > 0) trace.markers += notes.map { Marker(((it.first - store.originMicros) / FrameStore.MICROS_PER_TICK).toInt(), it.second) }.sortedBy { it.time }
            status = "${store.frameCount} frames, ${store.messages.size} messages, ${"%.1f".format(store.lastTime / FrameStore.TICKS_PER_SECOND)} s" +
                if (problems.isEmpty()) "" else "\n" + problems.joinToString("\n")
            busy = false
            revision++
        }
    }

    /** Decoded signals in the open data whose name, or whose message's name, contains [query] and that are not plotted yet; with the message's name. */
    fun signalsMatching(query: String, limit: Int): List<Pair<PlotKey, String>> {
        val needle = query.trim().lowercase()
        val found = ArrayList<Pair<PlotKey, String>>()
        if (needle.isEmpty()) return found
        for (message in store.messages) {
            val decoder = dbc.decoder(message.bus, message.id) ?: continue
            val wholeMessage = needle in decoder.name.lowercase()
            for (signal in decoder.signals) {
                if (!wholeMessage && needle !in signal.name.lowercase()) continue
                val key = PlotKey(message.bus, message.id, signal.name)
                if (key !in plot.keys) found += key to decoder.name
                if (found.size == limit) return found
            }
        }
        return found
    }

    // ---- markers: in the trace, and in the recording when there is one

    fun beginMarker() = PendingMarker(trace.cursorNow, live.now())

    fun addMarker(pending: PendingMarker, note: String) {
        trace.markers += Marker(pending.time, note)
        trace.markers.sortBy { it.time }
        if (pending.fileMicros != null) live.mark(pending.fileMicros, note)
    }

    // ---- comparing with another log

    /** Reads [uris] only to count, per message, how often each bit was set; the frames themselves are not kept. */
    fun openCompare(uris: List<Uri>) {
        if (uris.isEmpty() || busy) return
        busy = true
        viewModelScope.launch {
            val name = context.displayName(uris[0]) + if (uris.size > 1) " +${uris.size - 1}" else ""
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val theirs = BitSummary()
                    for (uri in uris) context.openDecompressed(uri).use { LogReader.read(it, theirs) }
                    theirs to BitSummary().also { store.replay(it) }
                }
            }
            result.onSuccess { other = it.first; mine = it.second; otherName = name; status = "Comparing with $name" }
                .onFailure { status = "Compare: ${it.message ?: it.javaClass.simpleName}" }
            busy = false
        }
    }

    fun clearCompare() { other = null; mine = null; otherName = "" }

    override fun onCleared() = live.stop()

    companion object {
        /** Compares names with their numbers as numbers. */
        fun naturalCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                if (a[i].isDigit() && b[j].isDigit()) {
                    var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                    var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                    val x = a.substring(i, ei).trimStart('0'); val y = b.substring(j, ej).trimStart('0')
                    val c = if (x.length != y.length) x.length - y.length else x.compareTo(y)
                    if (c != 0) return c
                    i = ei; j = ej
                } else {
                    if (a[i] != b[j]) return a[i] - b[j]
                    i++; j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }
}
