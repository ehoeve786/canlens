package com.ehoeve.canpilot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ehoeve.canpilot.core.Analysis
import com.ehoeve.canpilot.core.AutoDecode
import com.ehoeve.canpilot.core.FieldMatch
import com.ehoeve.canpilot.core.FrameStore
import com.ehoeve.canpilot.core.Signal
import com.ehoeve.canpilot.core.Suggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A moment at which the user knows what a signal's value was. */
class FindPoint(val time: Int) { var value by mutableStateOf("") }

/** Finding signals nobody has decoded yet: the search by value or by correlation, and the automatic decoder. */
class SignalState(
    private val store: FrameStore,
    private val scope: CoroutineScope,
    private val dbc: DbcState,
    private val trace: TraceState,
    private val report: (String) -> Unit,
) {
    /** The signal search: its inputs, its results, and a found signal on its way into the editor. */
    val findPoints = mutableStateListOf<FindPoint>()
    var findResults by mutableStateOf<List<FieldMatch>>(emptyList()); private set
    var findStatus by mutableStateOf(""); private set
    var findByCorrelation by mutableStateOf(false); private set
    var searching by mutableStateOf(false); private set
    var pendingSignal by mutableStateOf<Signal?>(null)
    /** Signals being edited, by their message's bus and id, so a draft survives a look at another tab. */
    val drafts = mutableStateMapOf<Pair<Int, Int>, SignalDraft>()

    /** What the automatic decoder proposed and has not been added to a DBC yet. */
    var autoResults by mutableStateOf<List<Suggestion>>(emptyList()); private set
    var autoStatus by mutableStateOf(""); private set
    var decoding by mutableStateOf(false); private set

    /** Other data is coming: results found in the old data go. */
    fun reset() {
        findResults = emptyList(); findPoints.clear(); findStatus = ""; autoResults = emptyList(); autoStatus = ""
        drafts.clear()
    }

    fun searchByValue(tolerance: Double) {
        val points = findPoints.mapNotNull { point -> point.value.trim().toDoubleOrNull()?.let { point.time to it } }
        if (points.isEmpty()) { findStatus = "Add a point and enter its value"; return }
        search(false) { Analysis.findByValue(store, points, tolerance) }
    }

    fun searchCorrelated(key: PlotKey) {
        val message = store.messages.firstOrNull { it.bus == key.bus && it.id == key.id }
        val decoder = dbc.decoder(key.bus, key.id)
        val signal = decoder?.signals?.firstOrNull { it.name == key.signal }
        if (message == null || decoder == null || signal == null) { findStatus = "${key.signal} is not in this data"; return }
        search(true) { Analysis.findCorrelated(store, store.series(message, decoder, signal), message) }
    }

    private fun search(correlation: Boolean, find: () -> List<FieldMatch>) {
        if (searching) return
        searching = true
        findStatus = "Searching"
        scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching(find) }
            findByCorrelation = correlation
            findResults = result.getOrDefault(emptyList())
            findStatus = result.fold({ if (it.isEmpty()) "Nothing found" else "${it.size} candidates, best first. Tap one to check it." }, { "Search failed: ${it.message ?: it.javaClass.simpleName}" })
            searching = false
        }
    }

    fun decodeEverything() {
        if (decoding || store.frameCount == 0L) return
        decoding = true
        autoStatus = "Starting"
        val notes = trace.markers.map { it.time to it.note }
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { AutoDecode.run(store, { dbc.decoder(it.bus, it.id) }, notes) { step -> launch(Dispatchers.Main) { autoStatus = step } } }
            }
            autoResults = result.getOrDefault(emptyList())
            autoStatus = result.fold(
                { found -> if (found.isEmpty()) "Nothing left to decode" else "${found.size} suggestions, ${found.count { it.confidence >= NAMED }} of them with a name or a check behind them" },
                { "Decoding failed: ${it.message ?: it.javaClass.simpleName}" },
            )
            decoding = false
        }
    }

    /** Adds [accepted] to the DBCs and takes them off the list. */
    fun accept(accepted: List<Suggestion>) {
        dbc.add(accepted)
        autoResults = autoResults - accepted.toSet()
        report("${accepted.size} signals added. Export the DBC to keep a copy outside the app.")
    }

    companion object {
        /** Suggestions from this confidence up have a name or a verification behind them; below it they are plain layout guesses. */
        const val NAMED = 0.7
    }
}
