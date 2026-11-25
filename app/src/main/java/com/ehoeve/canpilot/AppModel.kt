package com.ehoeve.canpilot

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ehoeve.canpilot.core.Align
import com.ehoeve.canpilot.core.BitSummary
import com.ehoeve.canpilot.core.Export
import com.ehoeve.canpilot.core.Filter
import com.ehoeve.canpilot.core.FrameStore
import com.ehoeve.canpilot.core.LogReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The open data and what ties the parts together: loading logs, and resetting every part when other data comes.
 * The parts keep their own state: [dbc], [plot], [trace], [live], [signals], [tx]; [session] saves and restores all of it.
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
    val live = LiveState(application, preferences, viewModelScope, store, { status = it }, { liveStarted() }) { revision++ }
    val trace = TraceState(store, preferences) { live.source != LiveSource.NONE }
    val signals = SignalState(store, viewModelScope, dbc, trace) { status = it }
    val tx = TxState(application, store, viewModelScope, live, dbc)
    val session = SessionState(this, application, preferences)

    /** The log files the open data came from, and those merged onto its timeline after them; a session reopens both. */
    var logs: List<Uri> = emptyList(); private set
    var mergedLogs: List<Uri> = emptyList(); private set

    /** Another log to compare with, summed up bit by bit, and the same summary of the open data. */
    var other by mutableStateOf<BitSummary?>(null); private set
    var mine by mutableStateOf<BitSummary?>(null); private set
    var otherName by mutableStateOf(""); private set

    private fun liveStarted() {
        logs = emptyList(); mergedLogs = emptyList()
        reset(0, true, ChartView(0f, LIVE_WINDOW_SECONDS))
    }

    private fun reset(cursor: Int, follow: Boolean, view: ChartView) {
        trace.reset(cursor, follow)
        plot.view = view
        signals.reset()
        clearCompare()
    }

    /** [then] runs once the logs are loaded and every part was reset for them. */
    fun openLogs(uris: List<Uri>, then: () -> Unit = {}) {
        if (uris.isEmpty() || busy) return
        keepReadable(uris)
        logs = uris; mergedLogs = emptyList()
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
            then()
        }
    }

    /** Empties the workspace: no data, no plots, no marks. */
    fun closeLogs() {
        live.stop()
        live.recorded = null
        store.clear()
        logs = emptyList(); mergedLogs = emptyList()
        plot.keys.clear()
        reset(0, false, ChartView(0f, 1f))
        status = "Open a log or start a live stream"
        revision++
    }

    /** Lets the app read [uris] again after a restart, where the document's provider allows it. */
    private fun keepReadable(uris: List<Uri>) {
        for (uri in uris) runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    // ---- a second recording of the same drive, on the same timeline

    /**
     * Adds the frames of [uris] to the open data, in time order with it. Their clock is matched to the open data's by
     * the frames both recorded; their buses are numbered from [MERGED_BUS] up (then 2 × [MERGED_BUS], ...) so the
     * recordings stay apart, and are decoded like the bus they came from.
     */
    fun mergeLogs(uris: List<Uri>, then: () -> Unit = {}) {
        if (uris.isEmpty() || busy || store.frameCount == 0L) return
        keepReadable(uris)
        busy = true
        status = "Reading the log to merge"
        val busOffset = MERGED_BUS * (mergedLogs.size + 1)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val other = FrameStore()
                    for (uri in uris) context.openDecompressed(uri).use { LogReader.read(it, other) }
                    if (other.frameCount == 0L) throw IOException("no CAN frames in it")
                    val matched = Align.offsetMicros(store, other)
                    // without frames in common: trust the two clocks if they are within a day, else line up the starts
                    val shift = matched ?: if (Math.abs(store.originMicros - other.originMicros) < DAY_MICROS) 0L else store.originMicros - other.originMicros
                    val added = other.frameCount
                    Triple(store.merge(other, shift, busOffset), matched, added)
                }
            }
            result.onSuccess { (moved, matched, added) ->
                mergedLogs = mergedLogs + uris
                // time 0 moved if the merged log starts earlier: everything noted in the old times moves along
                trace.cursor += moved
                trace.markA = trace.markA?.plus(moved); trace.markB = trace.markB?.plus(moved)
                for (i in trace.markers.indices) trace.markers[i] = Marker(trace.markers[i].time + moved, trace.markers[i].note)
                signals.reset()
                plot.view = ChartView(seconds(store.firstTime), seconds(store.lastTime))
                status = "$added frames merged as bus $busOffset and up. " +
                    (if (matched != null) "Clocks matched on shared frames: the merged log was moved by %+.3f s.".format(matched / 1e6) else "No shared frames to match the clocks on, so the times are as recorded.") +
                    if (store.truncated) "\nMemory limit reached: the end is missing." else ""
            }.onFailure { status = "Merge: ${it.message ?: it.javaClass.simpleName}" }
            busy = false
            revision++
            then()
        }
    }

    // ---- export

    /** Writes the frames the Frames tab lists (all of them, or those its filter lets through), optionally only between marks A and B. */
    fun exportFrames(uri: Uri, csv: Boolean, window: Boolean) {
        val filter = runCatching { Filter.compile(trace.filter, store.messages) { dbc.decoder(it.bus, it.id) } }.getOrNull()
        val (from, to) = markWindow(window)
        export(uri) { out -> "${Export.frames(store, store.RowScan(filter).update(), from, to, csv, out)} frames saved" }
    }

    /** Writes the plotted signals' decoded values, optionally only between marks A and B. */
    fun exportSignals(uri: Uri, window: Boolean) {
        val keys = plot.keys.toList()
        val (from, to) = markWindow(window)
        export(uri) { out ->
            val named = keys.mapNotNull { key ->
                val message = store.messages.firstOrNull { it.bus == key.bus && it.id == key.id } ?: return@mapNotNull null
                val decoder = dbc.decoder(key.bus, key.id) ?: return@mapNotNull null
                val signal = decoder.signals.firstOrNull { it.name == key.signal } ?: return@mapNotNull null
                "${key.bus}:${hexId(key.id)}:${key.signal}" to store.series(message, decoder, signal)
            }
            Export.signals(named, seconds(from), seconds(to), out)
            "${named.size} signals saved"
        }
    }

    private fun markWindow(only: Boolean): Pair<Int, Int> {
        val a = trace.markA
        val b = trace.markB
        return if (only && a != null && b != null) minOf(a, b) to maxOf(a, b) else Int.MIN_VALUE to Int.MAX_VALUE
    }

    private fun export(uri: Uri, write: (Appendable) -> String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("can't write there")).bufferedWriter(Charsets.US_ASCII).use(write) }
            }
            status = result.getOrElse { "Not saved: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    /** Decoded signals in the open data whose name, or whose message's name, contains [query] and that are not plotted yet; with the message's name. */
    fun signalsMatching(query: String, limit: Int): List<Pair<PlotKey, String>> {
        val needle = query.trim().lowercase()
        val found = ArrayList<Pair<PlotKey, String>>()
        if (needle.isEmpty()) return found
        for (message in store.messages) {
            // comma buses 128 and up are its own sent frames echoed back: copies that would double every hit
            if (message.bus >= 128) continue
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

    /** Notes of the last markers, newest first: one tap repeats a step of an in-car test ("passenger in"). */
    var recentNotes by mutableStateOf(preferences.getString("recentNotes", "")!!.split('\n').filter { it.isNotEmpty() })
        private set

    fun addMarker(pending: PendingMarker, note: String) {
        // the numbered default says nothing worth repeating
        if (!note.matches(Regex("Marker \\d+"))) {
            recentNotes = (listOf(note) + (recentNotes - note)).take(8)
            preferences.edit().putString("recentNotes", recentNotes.joinToString("\n")).apply()
        }
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

    override fun onCleared() { tx.close(); live.stop() }

    init { session.restore() }

    companion object {
        /** A merged recording's buses are numbered from here: bus 1 of the first merged log is bus 11. */
        const val MERGED_BUS = 10
        private const val DAY_MICROS = 86_400_000_000L

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
