package com.ehoeve.canlens

import com.ehoeve.canlens.core.DbcMessage
import com.ehoeve.canlens.core.Suggestion
import com.ehoeve.canlens.core.Signal
import com.ehoeve.canlens.core.AutoDecode
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
import com.ehoeve.canlens.core.Align
import com.ehoeve.canlens.core.BitSummary
import com.ehoeve.canlens.core.Export
import com.ehoeve.canlens.core.Filter
import com.ehoeve.canlens.core.FrameSink
import com.ehoeve.canlens.core.FrameStore
import com.ehoeve.canlens.core.LogReader
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
    private val preferences = application.getSharedPreferences("canlens", Context.MODE_PRIVATE)

    /** As many chunks as 40 % of the heap holds: the rest is room for decoding, plotting and merging what is loaded. */
    private val maxChunks = (Runtime.getRuntime().maxMemory() * 2 / 5 / FrameStore.CHUNK_BYTES).toInt().coerceAtLeast(8)
    val store = FrameStore(maxChunks)
    /** Where the open logs stopped loading (source clock), so the next part can be loaded; null when they loaded whole. */
    var nextPart: Long? by mutableStateOf(null); private set
    /** Set by Cancel while logs load: the reading stops, and what was read so far stays. */
    @Volatile var cancelling = false
    /** Goes up whenever the store's content changed; composables read it to refresh. */
    var revision by mutableIntStateOf(0); private set
    var status by mutableStateOf("Open a log or start a live stream")
    var busy by mutableStateOf(false); private set

    val plot = PlotState()
    val dbc = DbcState(application, preferences, viewModelScope, plot.keys) { status = it }
    val live = LiveState(application, preferences, viewModelScope, store, { status = it }, { liveStarted() }) { revision++ }
    val trace = TraceState(store, preferences) { live.source != LiveSource.NONE }
    val signals = SignalState(store, viewModelScope, dbc, trace, preferences, { live.source != LiveSource.NONE }) { status = it }
    /** A spoken test in the car; it records, so its markers land in the log as well. */
    val guided = GuidedTest(application, viewModelScope) { note -> addMarker(beginMarker(), note, remember = false) }
    val tx = TxState(application, store, viewModelScope, live, dbc)
    private val api = LocalApi(this, application, preferences).also { it.start() }
    val session = SessionState(this, application, preferences)

    /** The log files the open data came from, and those merged onto its timeline after them; a session reopens both. */
    var logs: List<Uri> = emptyList(); private set
    var mergedLogs: List<Uri> = emptyList(); private set

    /** Another log to compare with, summed up bit by bit, and the same summary of the open data. */
    var other by mutableStateOf<BitSummary?>(null); private set
    var mine by mutableStateOf<BitSummary?>(null); private set
    var otherName by mutableStateOf(""); private set

    private fun liveStarted() {
        logs = emptyList(); mergedLogs = emptyList(); nextPart = null; session.part = null
        reset(0, true, ChartView(0f, LIVE_WINDOW_SECONDS))
    }

    private fun reset(cursor: Int, follow: Boolean, view: ChartView) {
        trace.reset(cursor, follow)
        plot.view = view
        signals.reset()
        clearCompare()
    }

    /** [then] runs once the logs are loaded and every part was reset for them. */
    fun openLogs(uris: List<Uri>, from: Long? = null, then: () -> Unit = {}) {
        if (uris.isEmpty() || busy) return
        keepReadable(uris)
        logs = uris; mergedLogs = emptyList(); session.part = from
        live.stop()
        clearCompare()
        busy = true
        viewModelScope.launch {
            val problems = ArrayList<String>()
            val notes = ArrayList<Pair<Long, String>>()
            var stopped = false
            withContext(Dispatchers.IO) {
                store.clear()
                store.dropOldest = false
                // the next part of a log too big to load at once: the frames before [from] are read past; Cancel stops the reading
                cancelling = false
                var read = 0L
                val sink = FrameSink { time, bus, id, data, offset, length -> read++; !cancelling && ((from != null && time < from) || store.frame(time, bus, id, data, offset, length)) }
                // route segments in order: natural sort puts --2-- before --10--
                val files = uris.map { context.displayName(it) to it }.sortedWith { a, b -> naturalCompare(a.first, b.first) }
                for ((index, file) in files.withIndex()) {
                    if (cancelling) { stopped = true; break }
                    withContext(Dispatchers.Main) { status = "Reading ${file.first} (${index + 1}/${files.size})" }
                    val before = store.frameCount
                    read = 0L
                    try {
                        context.openDecompressed(file.second).use { LogReader.read(it, sink) { time, note -> if (from == null || time >= from) notes += time to note } }
                        if (cancelling) { stopped = true; break }
                        // in the next part a file may lie wholly before [from]: it has frames, just none kept
                        val empty = if (from == null) store.frameCount == before else read == 0L
                        if (empty) problems += "${file.first}: no CAN frames"
                    } catch (e: Exception) {
                        // a reader stopped by Cancel may throw: that is no fault of the file
                        if (!cancelling) problems += "${file.first}: ${e.message ?: e.javaClass.simpleName}"
                    } catch (e: OutOfMemoryError) {
                        problems += "${file.first}: out of memory"
                    }
                    if (store.truncated) { problems += "memory limit reached, the rest was not loaded"; break }
                }
            }
            if (stopped && !store.truncated) problems += "cancelled, the rest was not loaded"
            // after Cancel too the rest can be loaded as the next part: from the last frame kept, or from where this part began
            val end = if (store.frameCount > 0) store.originMicros + store.lastTime * FrameStore.MICROS_PER_TICK else from
            nextPart = if (store.truncated || stopped) end else null
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
        logs = emptyList(); mergedLogs = emptyList(); nextPart = null; session.part = null
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
            var cut = false
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // only what still fits in the open data's budget: the merge drops the rest anyway
                    val room = maxChunks - store.chunks.size
                    if (room <= 0) throw IOException("memory limit reached, no room for it")
                    val other = FrameStore(room)
                    for (uri in uris) context.openDecompressed(uri).use { LogReader.read(it, other) }
                    cut = other.truncated
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
                    if (store.truncated || cut) "\nMemory limit reached: the end is missing." else ""
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
        // a candump log compressed and with the markers, as a recording is: what Open reads back
        export(uri, zstd = !csv) { out -> "${Export.frames(store, store.RowScan(filter).update(), from, to, csv, out, trace.markers.map { it.time to it.note })} frames saved" }
    }

    /** Writes the plotted signals' decoded values, optionally only between marks A and B. */
    fun exportSignals(uri: Uri, window: Boolean) {
        val keys = plot.keys.toList()
        val (from, to) = markWindow(window)
        export(uri) { out ->
            val named = keys.mapNotNull { key ->
                val message = store.messages.firstOrNull { it.bus == key.bus && it.id == key.id } ?: return@mapNotNull null
                // a plotted Decode suggestion isn't in a DBC yet: it goes along as plotted
                val (decoder, signal) = plot.unsaved[key] ?: dbc.decoder(key.bus, key.id)?.let { d -> d.signals.firstOrNull { it.name == key.signal }?.let { d to it } } ?: return@mapNotNull null
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

    private fun export(uri: Uri, zstd: Boolean = false, write: (Appendable) -> String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("can't write there")
                    (if (zstd) com.github.luben.zstd.ZstdOutputStream(stream) else stream).bufferedWriter(Charsets.US_ASCII).use(write)
                }
            }
            status = result.getOrElse { "Not saved: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    /**
     * Signals in the open data whose name, or whose message's name or id ("575", "0x575"), contains [query] and that
     * are not plotted yet; with the message's name. Decoded ones, then Decode's suggestions not in a DBC yet.
     */
    fun signalsMatching(query: String, limit: Int): List<Pair<PlotKey, String>> {
        val needle = query.trim().lowercase().removePrefix("0x")
        val found = ArrayList<Pair<PlotKey, String>>()
        if (needle.isEmpty()) return found
        for (message in store.messages) {
            // comma buses 128 and up are its own sent frames echoed back: copies that would double every hit
            if (message.bus >= 128) continue
            val decoder = dbc.decoder(message.bus, message.id) ?: continue
            val wholeMessage = needle in decoder.name.lowercase() || needle in hexId(message.id).lowercase()
            for (signal in decoder.signals) {
                if (!wholeMessage && needle !in signal.name.lowercase()) continue
                val key = PlotKey(message.bus, message.id, signal.name)
                if (key !in plot.keys) found += key to decoder.name
                if (found.size == limit) return found
            }
        }
        for (s in signals.autoResults) {
            if (s.message.bus >= 128 || (needle !in s.signal.name.lowercase() && needle !in hexId(s.message.id).lowercase())) continue
            val key = PlotKey(s.message.bus, s.message.id, s.signal.name)
            if (key !in plot.keys && found.none { it.first == key }) found += key to "Decode's suggestion"
            if (found.size == limit) break
        }
        return found
    }

    /**
     * The message and signal [key] stands for: from the DBCs, else plotted unsaved, else one of Decode's suggestions
     * (a paged one with its message's selector). [saved]: whether it is the DBCs'.
     */
    fun signalOf(key: PlotKey): Triple<DbcMessage, Signal, Boolean>? {
        dbc.decoder(key.bus, key.id)?.let { d -> d.signals.firstOrNull { it.name == key.signal }?.let { return Triple(d, it, true) } }
        plot.unsaved[key]?.let { return Triple(it.first, it.second, false) }
        val s = signals.autoResults.firstOrNull { it.message.bus == key.bus && it.message.id == key.id && it.signal.name == key.signal } ?: return null
        val selector = if (s.signal.muxValue < 0) null else signals.autoResults.firstOrNull { it.message === s.message && it.signal.isMultiplexor }?.signal
        return Triple(DbcMessage(s.message.id, "NEW_${hexId(s.message.id)}", s.message.data.size, listOfNotNull(selector, s.signal)), s.signal, false)
    }

    /** Claude's names for Decode's events (number to name and why), from [askClaude], and how asking went. */
    var claudeNames by mutableStateOf<Map<Int, Pair<String, String>>>(emptyMap()); private set
    var claudeStatus by mutableStateOf(""); private set
    /** The user's Anthropic API key: kept in the app's own storage (not backed up), sent only to api.anthropic.com. */
    var claudeKey: String
        get() = preferences.getString("claudeKey", null).orEmpty()
        set(value) { preferences.edit().putString("claudeKey", value.trim()).apply() }

    /** Asks Claude what each of [events] was, from what changed then: its fields and the rare named signals around it. */
    fun askClaude(events: Map<Int, List<Suggestion>>) {
        val key = claudeKey
        if (key.isEmpty() || events.isEmpty()) return
        claudeStatus = "Asking Claude…"
        viewModelScope.launch {
            claudeStatus = runCatching {
                val report = withContext(Dispatchers.Default) { eventReport(events) }
                claudeNames = withContext(Dispatchers.IO) { Claude.nameEvents(key, report) }
                // each named event's fields get the name (DBC style), found again by their bits: a decode may have run meanwhile
                val now = signals.events().entries.associate { (number, fields) -> fields.joinToString { signals.key(it) } to number }
                val named = claudeNames.count { (number, answer) ->
                    val current = events[number]?.let { fields -> now[fields.joinToString { signals.key(it) }] }
                    (answer.first != "unclear" && current != null).also { if (it) signals.nameEvent(current!!, answer.first) }
                }
                "Claude named $named of ${events.size} events: check them on a plot before you add them"
            }.getOrElse { "Claude: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    /** Events already sent to Claude, by their fields' bits: a stream's next decode doesn't ask about them again. */
    private val asked = HashSet<String>()

    init {
        // after each decode, the events not asked about yet go to Claude, when a key is set
        signals.afterDecode = {
            if (claudeKey.isNotEmpty()) askClaude(signals.events().filterValues { fields -> asked.add(fields.joinToString { signals.key(it) }) })
        }
    }

    /** Each event's moment, its fields' values before and after it, and the rare named signals that changed within a second. */
    private fun eventReport(events: Map<Int, List<Suggestion>>): String = buildString {
        val second = FrameStore.TICKS_PER_SECOND.toInt()
        fun change(key: PlotKey, at: Int): String? {
            val (decoder, signal) = signalOf(key) ?: return null
            val message = store.messages.firstOrNull { it.bus == key.bus && it.id == key.id } ?: return null
            val s = store.series(message, decoder, signal, at - 2 * second, at + 2 * second)
            if (s.size == 0) return null
            fun v(x: Float) = signal.valueNames[x.toLong()] ?: "%g".format(x)
            return "bus ${key.bus} 0x${hexId(key.id)} ${decoder.name}.${key.signal} (bits ${signal.start}+${signal.size}): ${v(s.values[0])} -> ${v(s.values[s.size - 1])}"
        }
        append("Car: Tesla Model Y. Times in seconds from the log's start; values 2 s before -> 2 s after each moment.\n")
        for ((number, fields) in events) {
            val at = fields[0].at ?: continue
            append("\nEvent $number at %.2f s%s\nIts undocumented fields:\n".format(seconds(at), store.clock(at)?.let { " ($it)" }.orEmpty()))
            for (f in fields) change(PlotKey(f.message.bus, f.message.id, f.signal.name), at)?.let { append("  $it\n") }
            append("Documented signals that changed within a second of it and rarely otherwise:\n")
            for ((k, _) in changedSignals(at - second, at + second).take(15)) change(k, at)?.let { append("  $it\n") }
        }
    }

    /** Plots [key]: a DBC signal, or one of Decode's suggestions (kept with its message until it is in a DBC). False when neither has it. */
    fun plot(key: PlotKey): Boolean {
        if (key in plot.keys) return true
        val (message, signal, saved) = signalOf(key) ?: return false
        if (!saved) plot.unsaved[key] = message to signal
        plot.keys += key
        return true
    }

    /**
     * For each moment in [times] (ascending), the rarest named signal (openpilot's bus 8 among them) that changed within
     * half a second of it, or null: what an event most likely was. One pass for all of them.
     */
    fun namedAt(times: IntArray): List<String?> {
        val half = FrameStore.TICKS_PER_SECOND.toInt() / 2
        val (total, windows) = store.windowFlips(IntArray(times.size) { times[it] - half }, 2 * half)
        val best = arrayOfNulls<Pair<String, Int>>(times.size)
        for (message in store.messages) {
            if (message.bus >= 128) continue
            val inWindows = windows.getOrNull(message.index) ?: continue
            val all = total.getOrNull(message.index) ?: continue
            for (s in dbc.decoder(message.bus, message.id)?.signals ?: continue) {
                val changes = s.bits.sumOf { if (it < all.size) all[it] else 0 }
                if (changes == 0 || changes * 20 >= message.count) continue
                for (w in times.indices) {
                    val counts = inWindows[w] ?: continue
                    if (s.bits.any { it < counts.size && counts[it] > 0 } && changes < (best[w]?.second ?: Int.MAX_VALUE)) best[w] = s.name to changes
                }
            }
        }
        return best.map { it?.first }
    }

    /**
     * The named signals (openpilot's bus 8 among them) that changed between [from] and [to], with how often they
     * changed in the whole log: only rare ones (a door, a gear; not a speed or a counter), the rarest first. One pass.
     */
    fun changedSignals(from: Int, to: Int): List<Pair<PlotKey, Int>> =
        store.bitActivity(from, to).filterNotNull().flatMap { a ->
            val decoder = if (a.message.bus >= 128) null else dbc.decoder(a.message.bus, a.message.id)
            decoder?.signals.orEmpty().mapNotNull { s ->
                val changes = s.bits.sumOf { if (it < a.inside.size) a.outside[it] + a.inside[it] else 0 }
                val moved = s.bits.any { it < a.inside.size && a.inside[it] > 0 }
                if (moved && changes * 20 < a.message.count) PlotKey(a.message.bus, a.message.id, s.name) to changes else null
            }
        }.sortedBy { it.second }

    /**
     * A plot list pasted into a search, as an assistant writes one or as a CSV export names its columns:
     * `bus:id:signal` (id in hex) is a signal to plot, `@37.4` or `@37.4=note` a moment (seconds) to mark.
     * Null when [text] holds neither.
     */
    fun plotList(text: String): Pair<List<PlotKey>, List<Marker>>? {
        val keys = PLOT_ITEM.findAll(text).map { PlotKey(it.groupValues[1].toInt(), it.groupValues[2].toLong(16).toInt() and 0x1FFFFFFF, it.groupValues[3]) }.distinct().toList()
        val marks = MARK_ITEM.findAll(text).map { Marker(ticks(it.groupValues[1].toFloat()), it.groupValues[2].ifEmpty { "Check" }) }.toList()
        return if (keys.isEmpty() && marks.isEmpty()) null else keys to marks
    }

    /** Plots and marks a [plotList]; says what wasn't in the open data. */
    fun applyPlotList(list: Pair<List<PlotKey>, List<Marker>>) {
        val missing = list.first.filterNot(::plot)
        val new = list.second.filter { m -> trace.markers.none { it.time == m.time } }
        trace.markers += new
        trace.markers.sortBy { it.time }
        status = "Plotted ${list.first.size - missing.size} signals, marked ${new.size} moments" +
            if (missing.isEmpty()) "" else "; not in this log or its DBCs: " + missing.joinToString { it.signal }
    }

    /**
     * Plots the fields of an event (Decode's suggestions, not in a DBC yet) in place of what was plotted, around its
     * moment, with the cursor on it; then the named signals that changed within a second of it (openpilot's bus 8
     * among them), which say what the event was. Only ones that change rarely: a door or a gear, not a speed or a
     * counter, which change at any moment. The rarest first, at most [EVENT_NAMED].
     */
    fun plotEvent(fields: List<Suggestion>) {
        val at = fields.firstOrNull()?.at ?: return
        plot.keys.clear()
        plot.unsaved.clear()
        for (s in fields) {
            val key = PlotKey(s.message.bus, s.message.id, s.signal.name)
            plot.unsaved[key] = DbcMessage(s.message.id, "NEW_${hexId(s.message.id)}", s.message.data.size, listOf(s.signal)) to s.signal
            plot.keys += key
        }
        trace.cursor = at
        trace.followLive = false
        plot.view.show(seconds(at) - 10, seconds(at) + 10)
        val second = FrameStore.TICKS_PER_SECOND.toInt()
        viewModelScope.launch {
            val named = withContext(Dispatchers.Default) { changedSignals(at - second, at + second).take(EVENT_NAMED).map { it.first } }
            if (trace.cursor == at) plot.keys += named.filter { it !in plot.keys }
        }
    }

    /** What the number the camera reads off the car's screen is ("range"). */
    var readingName: String = preferences.getString("readingName", "")!!
        set(value) { field = value; preferences.edit().putString("readingName", value).apply() }

    /** The steps of the user's own guided test, a line each. */
    var customTest: String = preferences.getString("customTest", "")!!
        set(value) { field = value; preferences.edit().putString("customTest", value).apply() }

    // ---- markers: in the trace, and in the recording when there is one

    /**
     * [atCursor]: the user's own marker, where the cursor is. Automatic ones (camera readings, guided-test steps) mark
     * the newest frame while a stream runs, also when the user moved the cursor away from it.
     */
    fun beginMarker(atCursor: Boolean = false) =
        PendingMarker(if (atCursor || live.source == LiveSource.NONE) trace.cursorNow else store.lastTime, live.now())

    /** Notes of the last markers, newest first: one tap repeats a step of an in-car test ("passenger in"). */
    var recentNotes by mutableStateOf(preferences.getString("recentNotes", "")!!.split('\n').filter { it.isNotEmpty() })
        private set

    /** [remember]: offer the note again as a chip; a guided test's notes would push the user's own out. */
    fun addMarker(pending: PendingMarker, note: String, remember: Boolean = true) {
        // the numbered default says nothing worth repeating
        if (remember && !note.matches(Regex("Marker \\d+"))) {
            // of a reading ("range=312") the name is offered again, to be completed with the next value
            val again = AutoDecode.READING.find(note)?.let { it.groupValues[1] + "=" } ?: note
            recentNotes = (listOf(again) + (recentNotes - again)).take(8)
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

    override fun onCleared() { tx.close(); guided.close(); live.stop(); api.stop() }

    init { session.restore() }

    companion object {
        /** A merged recording's buses are numbered from here: bus 1 of the first merged log is bus 11. */
        const val MERGED_BUS = 10
        private val PLOT_ITEM = Regex("""\b(\d{1,3}):(?:0x)?([0-9A-Fa-f]{1,8}):([A-Za-z_]\w*)""")
        private val MARK_ITEM = Regex("""@(\d+(?:\.\d+)?)(?:=([^\s,;]+))?""")
        /** Named signals an event's plot adds next to its fields. */
        private const val EVENT_NAMED = 12
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
