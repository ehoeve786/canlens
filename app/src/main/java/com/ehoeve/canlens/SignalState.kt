package com.ehoeve.canlens

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ehoeve.canlens.core.Analysis
import com.ehoeve.canlens.core.AutoDecode
import java.net.URL
import com.ehoeve.canlens.core.Dbc
import com.ehoeve.canlens.core.DbcMessage
import com.ehoeve.canlens.core.FieldMatch
import com.ehoeve.canlens.core.FrameStore
import com.ehoeve.canlens.core.Signal
import com.ehoeve.canlens.core.Suggestion
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
    private val preferences: SharedPreferences,
    private val live: () -> Boolean,
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

    /**
     * Named and community suggestions the user turned down (left unticked when others were added), by bus, id and
     * bits. They stay off the list on later decodes, in later runs of the app too, until brought back.
     */
    private val rejected = preferences.getStringSet("rejected", emptySet())!!.toHashSet()
    var rejectedCount by mutableIntStateOf(rejected.size); private set
    /** What identifies a suggestion across decodes: its message and its bits. */
    fun key(s: Suggestion) = "${s.message.bus}:${s.message.id}:${s.signal.start}:${s.signal.size}:${s.signal.muxValue}"
    private fun saveRejected() { rejectedCount = rejected.size; preferences.edit().putStringSet("rejected", rejected.toHashSet()).apply() }

    fun restoreRejected() { rejected.clear(); saveRejected(); decodeEverything() }

    /** While a stream runs: decode again every 15 s, also away from the Decode screen, and say what got a name. */
    var keepDecoding by mutableStateOf(false); private set

    private var repeating: Job? = null

    fun keepDecoding(on: Boolean) {
        repeating?.cancel()
        keepDecoding = on
        repeating = if (!on) null else scope.launch {
            while (live()) { if (!decoding) decodeEverything(); delay(15_000) }
            keepDecoding = false
        }
    }

    /** The search and the decode running, if any; [generation] goes up when the data is other, so a late result is dropped. */
    private var searchJob: Job? = null
    private var decodeJob: Job? = null
    private var generation = 0

    /** Other data is coming: results found in the old data go, and so does what is still being worked out on it. */
    fun reset() {
        generation++
        searchJob?.cancel(); searchJob = null; searching = false
        decodeJob?.cancel(); decodeJob = null; decoding = false
        findResults = emptyList(); findPoints.clear(); findStatus = ""; autoResults = emptyList(); autoStatus = ""
        drafts.clear()
    }

    fun searchByValue(tolerance: Double) {
        // "21,5" is read as 21.5
        val points = findPoints.mapNotNull { point -> point.value.trim().replace(',', '.').toDoubleOrNull()?.let { point.time to it } }
        if (points.isEmpty()) { findStatus = "Add a point and enter its value"; return }
        val skipped = findPoints.size - points.size
        search(false, if (skipped == 0) "" else " ($skipped of the points have no number and were left out)") { Analysis.findByValue(store, points, tolerance) }
    }

    fun searchCorrelated(key: PlotKey) {
        val message = store.messages.firstOrNull { it.bus == key.bus && it.id == key.id }
        val decoder = dbc.decoder(key.bus, key.id)
        val signal = decoder?.signals?.firstOrNull { it.name == key.signal }
        if (message == null || decoder == null || signal == null) { findStatus = "${key.signal} is not in this data"; return }
        search(true) { Analysis.findCorrelated(store, store.series(message, decoder, signal), message) }
    }

    /** [note]: said after the outcome, e.g. which inputs were left out. */
    private fun search(correlation: Boolean, note: String = "", find: () -> List<FieldMatch>) {
        if (searching) return
        searching = true
        findStatus = "Searching"
        val started = generation
        searchJob = scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching(find) }
            if (started != generation) return@launch
            findByCorrelation = correlation
            findResults = result.getOrDefault(emptyList())
            findStatus = result.fold({ if (it.isEmpty()) "Nothing found" else "${it.size} candidates, best first. Tap one to check it." }, { "Search failed: ${it.message ?: it.javaClass.simpleName}" }) + note
            searching = false
        }
    }

    fun decodeEverything() {
        if (decoding || store.frameCount == 0L) return
        decoding = true
        autoStatus = "Starting"
        // a marker is something done, or a number read off the car ("range=312")
        val (read, done) = trace.markers.map { it.time to it.note }.partition { AutoDecode.READING.matches(it.second) }
        // "21,5" and "−5" as well as "21.5" and "-5"
        val readings = read.mapNotNull { (time, note) -> AutoDecode.reading(note)?.let { (name, value) -> Triple(name.lowercase(), time, value) } }
            .groupBy({ it.first }, { it.second to it.third })
        val named = autoResults.filter { it.confidence >= NAMED }.mapTo(HashSet(), ::key)
        val started = generation
        decodeJob = scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val known = { message: FrameStore.Message -> dbc.decoder(message.bus, message.id) }
                    (AutoDecode.fromReadings(store, known, readings) + AutoDecode.run(store, known, done) { step -> launch(Dispatchers.Main) { if (started == generation) autoStatus = step } }).filter { key(it) !in rejected }
                }
            }
            if (started != generation) return@launch
            // a name given before (an event's, typed in) stays with the same bits of the same message, though a new run
            // no longer counts them as an event: they changed again, or the moment was dropped from a long stream
            val given = autoResults.filter { it.confidence >= NAMED }.associateBy { Triple(it.message, it.signal.start, it.signal.size) }
            autoResults = properNames(result.getOrDefault(emptyList()).map { s ->
                given[Triple(s.message, s.signal.start, s.signal.size)]?.takeIf { s.confidence < NAMED }
                    ?.let { Suggestion(s.message, s.signal.renamed(it.signal.name), it.evidence, NAMED, it.messageName, it.at) } ?: s
            })
            // decoding by itself during a stream: what is new is said, since nobody may be looking at the list
            val fresh = autoResults.filter { it.confidence >= NAMED && key(it) !in named }
            if (keepDecoding && fresh.isNotEmpty()) report("Decode found " + fresh.take(3).joinToString { "${hexId(it.message.id)} ${it.signal.name}" } + if (fresh.size > 3) " and ${fresh.size - 3} more" else "")
            autoStatus = result.fold(
                { found -> if (found.isEmpty()) "Nothing left to decode" else "${found.size} suggestions, ${found.count { it.confidence >= NAMED }} of them with a name or a check behind them" },
                { "Decoding failed: ${it.message ?: it.javaClass.simpleName}" },
            )
            decoding = false
            if (result.isSuccess) afterDecode?.invoke()
        }
    }

    /** Called after each decode that worked: the app asks Claude about events it hasn't asked about. */
    var afterDecode: (() -> Unit)? = null

    /** The events among the suggestions (fields that changed together once), by number. */
    fun events(): Map<Int, List<Suggestion>> =
        autoResults.filter { it.at != null }.groupBy { EVENT.find(it.signal.name)?.groupValues?.get(1)?.toInt() ?: 0 }.filterKeys { it > 0 }.toSortedMap()

    /** The prefix the DBCs give [message]'s signals (VCSEC); UNK_<id> for a message they don't name or call UNK_ or NEW_. */
    fun prefixOf(message: FrameStore.Message): String {
        val name = dbc.decoder(message.bus, message.id)?.name
        return if (name == null || name.startsWith("UNK_") || name.startsWith("NEW_")) "UNK_${hexId(message.id)}" else name.substringBefore('_')
    }

    /**
     * Decode's own names in the DBCs' style (message prefix, camelCase): COUNTER_0 becomes UNK_26D_counter, MUX_8 its
     * index, FLAG_33 flag33, M3_STATE_12 (page 3) m3State12, and a field that follows a known signal takes that signal's
     * name under this message's prefix (with Rate or Total when it follows its rate or running total). Events keep
     * theirs until they are named; two that come out the same in one message get their start bit.
     */
    private fun properNames(list: List<Suggestion>): List<Suggestion> {
        val renamed = list.map { s ->
            val name = s.signal.name
            val like = LIKE.find(name)
            val plain = PLAIN.find(name)
            val own = when {
                like != null -> like.groupValues[2].substringAfter('_') + when (like.groupValues[1]) { "RATE_" -> "Rate"; "TOTAL_" -> "Total"; else -> "" }
                plain != null -> {
                    val (page, kind, start) = plain.destructured
                    val word = KINDS.getValue(kind) + if (kind in NUMBERED) start else ""
                    if (page.isEmpty()) word else "m$page" + word.replaceFirstChar(Char::uppercaseChar)
                }
                else -> null
            }
            if (own == null) s else Suggestion(s.message, s.signal.renamed("${prefixOf(s.message)}_$own"), s.evidence, s.confidence, s.messageName, s.at)
        }
        val seen = renamed.groupingBy { it.message to it.signal.name }.eachCount()
        return renamed.map { s -> if ((seen[s.message to s.signal.name] ?: 0) < 2) s
            else Suggestion(s.message, s.signal.renamed(s.signal.name + s.signal.start), s.evidence, s.confidence, s.messageName, s.at) }
    }

    /**
     * Downloads the community's Tesla DBCs (opendbc's, and joshwardell's model3dbc) and lists what they know about
     * the messages here that the loaded DBCs don't, next to Decode's own suggestions.
     */
    fun checkCommunity() {
        if (decoding || store.frameCount == 0L) return
        decoding = true
        autoStatus = "Downloading the community DBCs"
        val started = generation
        decodeJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val community = COMMUNITY.map { (name, url) -> name to Dbc.parse(URL(url).openConnection().apply { connectTimeout = 15_000; readTimeout = 30_000 }.getInputStream().use { it.readBytes().toString(Charsets.ISO_8859_1) }, name) }
                    // the same id seen on several buses but saved in one file (the party DBC is for all buses): once
                    AutoDecode.fromCommunity(store, { dbc.decoder(it.bus, it.id) }, community).distinctBy { Triple(dbc.target(it.message), it.message.id, it.signal.name) }.filter { key(it) !in rejected }
                }
            }
            if (started != generation) return@launch
            result.onSuccess { found -> autoResults = autoResults.filter { !it.evidence.startsWith("From ") } + found }
            autoStatus = result.fold(
                { found ->
                    val fit = found.count { it.confidence >= NAMED }
                    when {
                        found.isEmpty() -> "The community DBCs know nothing your DBCs don't"
                        fit == found.size -> "$fit signals from the community DBCs, ticked: check them on a plot before you rely on them"
                        else -> "$fit signals from the community DBCs fit and are ticked; ${found.size - fit} don't fit your frames (among the unnamed fields, each with the reason)"
                    }
                },
                { "Couldn't get the community DBCs: ${it.message ?: it.javaClass.simpleName}" },
            )
            decoding = false
        }
    }

    /**
     * The suggestions as plain text, each with how its values behaved, to paste into a chat with an assistant that
     * helps name them: one line per field, in the log's own terms.
     */
    suspend fun report(): String = withContext(Dispatchers.Default) {
        buildString {
            append("CANLens decode report: ${store.frameCount} frames, ${store.messages.size} messages, %.0f s.\n".format((store.lastTime - store.firstTime) / FrameStore.TICKS_PER_SECOND))
            append("bus id signal bits: values seen (min..max, distinct, changes) | what Decode found\n")
            for (s in autoResults) {
                val signal = s.signal
                // a paged field is measured on its own page's frames: with the selector the DBC or Decode has for the message
                val selector = dbc.decoder(s.message.bus, s.message.id)?.multiplexor ?: autoResults.firstOrNull { it.message === s.message && it.signal.isMultiplexor }?.signal
                val series = store.series(s.message, DbcMessage(s.message.id, "", s.message.data.size, listOfNotNull(selector, signal)), signal)
                val stats = Analysis.stats(series, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
                val distinct = (0 until series.size).mapTo(HashSet()) { series.values[it] }.size
                append("${s.message.bus} %X ${signal.name} ${signal.start}+${signal.size}".format(s.message.id))
                if (signal.muxValue >= 0) append(" (page ${signal.muxValue})")
                if (stats != null) append(": %s..%s, %d values, %d changes".format(stats[0], stats[1], distinct, stats[3].toInt()))
                append(" | ${s.evidence}\n")
            }
        }
    }

    /**
     * Names every field of event [number] after what happened then, as the DBCs name signals: the message's prefix
     * (UI_, VCFRONT_; none for a message they don't have) and the words in camelCase, so "weather update" in a UI_
     * message is UI_weatherUpdate; the start bit is added only where the event has two fields in one message.
     * Lists them with the named ones.
     */
    fun nameEvent(number: Int, name: String) {
        val words = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.map { if (it == it.uppercase()) it.lowercase() else it }
        if (words.isEmpty()) return
        val fields = autoResults.filter { it.signal.name.startsWith("E${number}_") }
        autoResults = autoResults.map { s ->
            if (s !in fields) return@map s
            val prefix = prefixOf(s.message)
            val own = if (words.size > 1 && words[0].equals(prefix, true)) words.drop(1) else words
            val camel = own[0].replaceFirstChar(Char::lowercaseChar) + own.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
            val twin = fields.count { it.message === s.message } > 1
            Suggestion(s.message, s.signal.renamed("${prefix}_" + camel + if (twin) s.signal.start.toString() else ""), s.evidence, NAMED, at = s.at)
        }
    }

    /** Gives [old] another [signal] (a name, value names), as the user or the assistant chose: listed with the named ones. */
    fun rename(old: Suggestion, signal: Signal) {
        autoResults = autoResults.map { if (it === old) Suggestion(it.message, signal, it.evidence, maxOf(it.confidence, NAMED), it.messageName, it.at) else it }
    }

    /** Turns down [fields] (an event's) whatever they are, remembered like any rejection. */
    fun reject(fields: List<Suggestion>) {
        autoResults = autoResults - fields.toSet()
        if (rejected.addAll(fields.map(::key))) saveRejected()
    }

    /** Adds [accepted] to the DBCs and takes them off the list, with [declined]: shown and left unticked, so turned down. */
    fun accept(accepted: List<Suggestion>, declined: List<Suggestion> = emptyList()) {
        dbc.add(accepted)
        autoResults = autoResults - accepted.toSet() - declined.toSet()
        // plain layout guesses are only passed over; a name or a community signal turned down is remembered
        if (rejected.addAll(declined.filter { it.confidence >= NAMED || it.evidence.startsWith("From ") }.map(::key))) saveRejected()
        report(if (accepted.isEmpty()) "${declined.size} turned down" else "${accepted.size} signals added. Export the DBC to keep a copy outside the app.")
    }

    companion object {
        private val EVENT = Regex("^E(\\d+)_")
        private val LIKE = Regex("^(?:LIKE|COPY)_(RATE_|TOTAL_)?(.+)_\\d+$")
        private val PLAIN = Regex("^(?:M(\\d+)_)?(COUNTER|CHECKSUM|CRC|MUX|FLAG|STATE|FIELD)_?(\\d*)$")
        private val KINDS = mapOf("COUNTER" to "counter", "CHECKSUM" to "checksum", "CRC" to "crc", "MUX" to "index", "FLAG" to "flag", "STATE" to "state", "FIELD" to "value")
        /** Kinds a message can have several of: their name carries the start bit. */
        private val NUMBERED = setOf("FLAG", "STATE", "FIELD")
        private val COMMUNITY = listOf(
            "opendbc party" to "https://raw.githubusercontent.com/commaai/opendbc/master/opendbc/dbc/tesla_model3_party.dbc",
            "opendbc vehicle" to "https://raw.githubusercontent.com/commaai/opendbc/master/opendbc/dbc/tesla_model3_vehicle.dbc",
            "model3dbc" to "https://raw.githubusercontent.com/joshwardell/model3dbc/master/Model3CAN.dbc",
        )
        /** Suggestions from this confidence up have a name or a verification behind them; below it they are plain layout guesses. */
        const val NAMED = 0.7
    }
}
