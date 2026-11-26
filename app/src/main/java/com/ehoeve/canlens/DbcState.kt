package com.ehoeve.canlens

import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import com.ehoeve.canlens.core.Dbc
import com.ehoeve.canlens.core.DbcMessage
import com.ehoeve.canlens.core.FrameStore
import com.ehoeve.canlens.core.Signal
import com.ehoeve.canlens.core.Suggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * A DBC in use and the bus it decodes ([ALL_BUSES] for every bus). Kept as a private copy in the app's files, and every
 * change is also written to the file it was opened from (or `Download/CANLens/dbc/` for one the app started), which
 * outlives the app.
 */
class LoadedDbc(val dbc: Dbc, val bus: Int)

const val ALL_BUSES = -1

/**
 * The bus a frame was seen on, as the DBC files know it: openpilot logs what the comma sent with 128 added (192 when the
 * bus refused it), and a merged recording's buses are numbered from [AppModel.MERGED_BUS] up.
 */
fun sourceBus(bus: Int) = (bus and 63) % AppModel.MERGED_BUS

/** The DBC files in use, and every change to them. [plots] follows when a plotted signal is renamed or deleted. */
class DbcState(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val scope: CoroutineScope,
    private val plots: MutableList<PlotKey>,
    private val report: (String) -> Unit,
) {
    private val folder = File(context.filesDir, "dbc").apply { mkdirs() }
    private val io = Dispatchers.IO.limitedParallelism(1)    // one write at a time, in order
    /** The DBCs whose state before this run's first change has been put in the history. */
    private val backedUp = HashSet<String>()

    val files = mutableStateListOf<LoadedDbc>()

    init {
        for (file in folder.listFiles().orEmpty().filterNot { it.name.endsWith(PART_FILE) }.sortedBy { it.name }) {
            runCatching { files += LoadedDbc(Dbc.parse(file.readText(), file.name), preferences.getInt("bus:" + file.name, ALL_BUSES)) }
        }
    }

    /** The DBC message for a frame: a DBC assigned to that bus wins over one for all buses. */
    fun decoder(bus: Int, id: Int): DbcMessage? {
        val source = sourceBus(bus)
        if (source == PhoneSensors.PHONE_BUS) return PhoneSensors.DBC.messages[id]
        if (source == OpenpilotBus.BUS) return OpenpilotBus.DBC.messages[id]
        return files.firstOrNull { it.bus == source && id in it.dbc.messages }?.dbc?.messages?.get(id)
            ?: files.firstOrNull { it.bus == ALL_BUSES && id in it.dbc.messages }?.dbc?.messages?.get(id)
    }

    fun open(uri: Uri) {
        scope.launch {
            try {
                val name = context.displayName(uri).replace('/', '_')
                val bytes = withContext(Dispatchers.IO) { (context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open $name")).use { it.readBytes() } }
                val text = bytes.toString(Charsets.ISO_8859_1)
                val dbc = Dbc.parse(text, name)
                if (dbc.messages.isEmpty()) throw IOException("$name has no messages")
                withContext(Dispatchers.IO) { File(folder, name).writeWhole(text, Charsets.ISO_8859_1) }
                // changes go back to this file, also after a restart, as long as nothing else changed it
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    .fold({ preferences.edit().putString("uri:$name", uri.toString()).putString("seen:$name", fingerprint(bytes)) }, { preferences.edit().remove("uri:$name").remove("seen:$name") }).apply()
                // the app's earlier versions of a file by this name are not this one's
                forget(name)
                files.removeAll { it.dbc.name == name }
                files += LoadedDbc(dbc, preferences.getInt("bus:$name", ALL_BUSES))
            } catch (e: Exception) {
                report("DBC: ${e.message}")
            }
        }
    }

    fun setBus(index: Int, bus: Int) {
        val old = files[index]
        files[index] = LoadedDbc(old.dbc, bus)
        preferences.edit().putInt("bus:" + old.dbc.name, bus).apply()
    }

    fun remove(index: Int) {
        val old = files.removeAt(index)
        File(folder, old.dbc.name).delete()
        preferences.edit().remove("bus:" + old.dbc.name).remove("uri:" + old.dbc.name).remove("seen:" + old.dbc.name).apply()
        // Undo doesn't bring a removed DBC back
        forget(old.dbc.name)
    }

    /** Takes [name] out of the history, and backs it up again before its next change. */
    private fun forget(name: String) {
        history.removeAll { it.first == name }
        canUndo = history.isNotEmpty()
        backedUp.remove(name)
    }

    fun export(index: Int, uri: Uri) {
        val text = files[index].dbc.write()
        scope.launch(Dispatchers.IO) {
            val result = runCatching { (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("can't write")).use { it.write(text.toByteArray(Charsets.ISO_8859_1)) } }
            withContext(Dispatchers.Main) { report(result.fold({ "DBC saved" }, { "DBC not saved: ${it.message}" })) }
        }
    }

    /** Adds or replaces a signal in the DBC that decodes [message], or in a new DBC for its bus if none does. */
    fun saveSignal(message: FrameStore.Message, signal: Signal, replaces: String?) {
        val index = indexFor(files, message)
        val old = if (index >= 0) files[index] else LoadedDbc(Dbc("canlens-bus${sourceBus(message.bus)}.dbc", emptyMap()), sourceBus(message.bus))
        var dbc = old.dbc
        // a renamed signal keeps its status
        val status = replaces?.let { dbc.statuses[message.id to it] }
        if (replaces != null && replaces != signal.name) dbc = dbc.withoutSignal(message.id, replaces).withStatus(message.id, signal.name, status)
        dbc = dbc.withSignal(message.id, "NEW_%X".format(message.id), message.data.size, signal)
        if (replaces != null && replaces != signal.name) {
            val at = plots.indexOf(PlotKey(message.bus, message.id, replaces))
            val renamed = PlotKey(message.bus, message.id, signal.name)
            // a plot keeps one key per signal: if the new name was plotted already, the old one's plot goes
            if (at >= 0) { if (renamed in plots) plots.removeAt(at) else plots[at] = renamed }
        }
        replace(index, LoadedDbc(dbc, old.bus))
    }

    /** Names [message] in the DBC that decodes it, or in a new DBC for its bus if none does. A DBC name is letters, digits and _. */
    fun renameMessage(message: FrameStore.Message, name: String) {
        val clean = name.trim().replace(Regex("\\W"), "_")
        if (clean.isEmpty()) return
        val index = indexFor(files, message)
        val old = if (index >= 0) files[index] else LoadedDbc(Dbc("canlens-bus${sourceBus(message.bus)}.dbc", emptyMap()), sourceBus(message.bus))
        replace(index, LoadedDbc(old.dbc.withMessageName(message.id, clean, message.data.size), old.bus))
    }

    fun deleteSignal(message: FrameStore.Message, name: String) {
        val index = indexFor(files, message)
        plots.remove(PlotKey(message.bus, message.id, name))
        if (index >= 0) replace(index, LoadedDbc(files[index].dbc.withoutSignal(message.id, name), files[index].bus))
    }

    /** How sure the decoding of a signal is: guess, plotted or confirmed; null when not said. */
    fun status(bus: Int, id: Int, name: String): String? {
        val source = sourceBus(bus)
        return (files.firstOrNull { it.bus == source && id in it.dbc.messages } ?: files.firstOrNull { it.bus == ALL_BUSES && id in it.dbc.messages })?.dbc?.statuses?.get(id to name)
    }

    fun setStatus(message: FrameStore.Message, name: String, status: String?) {
        val index = indexFor(files, message)
        if (index >= 0) replace(index, LoadedDbc(files[index].dbc.withStatus(message.id, name, status), files[index].bus))
    }

    /** Adds every suggested signal to the DBC its message belongs in; each DBC file is written once. */
    fun add(suggestions: List<Suggestion>) {
        val working = files.toMutableList()
        val changed = LinkedHashSet<Int>()
        for (suggestion in suggestions) {
            val message = suggestion.message
            var index = indexFor(working, message)
            if (index < 0) { working += LoadedDbc(Dbc("canlens-bus${sourceBus(message.bus)}.dbc", emptyMap()), sourceBus(message.bus)); index = working.lastIndex }
            // a field every repeat of a marker test agreed on is confirmed; anything else Decode found is a guess
            working[index] = LoadedDbc(working[index].dbc.withSignal(message.id, suggestion.messageName ?: "NEW_%X".format(message.id), message.data.size, suggestion.signal)
                .withStatus(message.id, suggestion.signal.name, if (suggestion.confidence >= 0.95 && suggestion.signal.name.startsWith("EVT_")) "confirmed" else "guess"), working[index].bus)
            changed += index
        }
        val existing = files.size
        for (index in changed) replace(if (index < existing) index else -1, working[index])
    }

    /** The file a signal of [message] would be saved in: one for all buses covers the same id on each of them. */
    fun target(message: FrameStore.Message): String = indexFor(files, message).let { if (it >= 0) files[it].dbc.name else "canlens-bus${sourceBus(message.bus)}.dbc" }

    /** The DBC in [dbcs] a signal of [message] belongs in, or -1: one that has the message, else one for its bus, else one for all buses. */
    private fun indexFor(dbcs: List<LoadedDbc>, message: FrameStore.Message): Int {
        val bus = sourceBus(message.bus)
        val exact = dbcs.indexOfFirst { it.bus == bus && message.id in it.dbc.messages }
        if (exact >= 0) return exact
        val shared = dbcs.indexOfFirst { it.bus == ALL_BUSES && message.id in it.dbc.messages }
        if (shared >= 0) return shared
        return dbcs.indexOfFirst { it.bus == bus }.takeIf { it >= 0 } ?: dbcs.indexOfFirst { it.bus == ALL_BUSES }
    }

    /** Earlier versions of the DBCs the app changed, newest last: a file's name and what it was (null: it didn't exist). */
    private val history = ArrayDeque<Pair<String, LoadedDbc?>>()
    var canUndo by mutableStateOf(false); private set

    /** Puts back the DBC as it was before the last change the app made to it. */
    fun undo() {
        val (name, before) = history.removeLastOrNull() ?: return
        canUndo = history.isNotEmpty()
        val index = files.indexOfFirst { it.dbc.name == name }
        if (before == null) { if (index >= 0) remove(index) } else write(index, before)
        report("Undone")
    }

    private fun replace(index: Int, value: LoadedDbc) {
        history.addLast(value.dbc.name to files.firstOrNull { it.dbc.name == value.dbc.name })
        if (history.size > 30) history.removeFirst()
        canUndo = true
        write(index, value)
    }

    private fun write(index: Int, value: LoadedDbc) {
        if (index >= 0) files[index] = value else files += value
        preferences.edit().putInt("bus:" + value.dbc.name, value.bus).apply()
        val text = value.dbc.write()
        val name = value.dbc.name
        val first = backedUp.add(name)
        scope.launch(io) {
            if (first) runCatching { backUp(name) }
            runCatching { File(folder, name).writeWhole(text, Charsets.ISO_8859_1) }
            val bytes = text.toByteArray(Charsets.ISO_8859_1)
            try {
                var uri = outside(name)
                // changed by something else since the app read or wrote it: that stays, the app's version goes to a new copy
                val changed = changedOutside(name, uri)
                if (changed) { preferences.edit().remove("uri:$name").remove("seen:$name").apply(); uri = outside(name) }
                (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("can't write the file")).use { it.write(bytes) }
                preferences.edit().putString("seen:$name", fingerprint(bytes)).apply()
                if (changed) withContext(Dispatchers.Main) { report("$name was changed outside the app, so it was left as it is: the app's version is in a new copy in Download/CANLens/dbc/") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // gone, or no longer allowed: the next change goes to a new copy; anything else may pass, the file is kept
                val lost = e is SecurityException || e is FileNotFoundException
                if (lost) preferences.edit().remove("uri:$name").remove("seen:$name").apply()
                withContext(Dispatchers.Main) { report("$name: only saved inside the app this time (${e.message ?: "can't write the file"})") }
            }
        }
    }

    /** Whether the file at [uri] differs from what the app last read from it or wrote to it; false when that isn't known. */
    private fun changedOutside(name: String, uri: Uri): Boolean {
        val seen = preferences.getString("seen:$name", null) ?: return false
        val now = (context.contentResolver.openInputStream(uri) ?: throw IOException("can't read the file")).use { it.readBytes() }
        return fingerprint(now) != seen
    }

    private fun fingerprint(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Copies [name] as it is before this run's first change to `Download/CANLens/dbc/history/`, under its name and
     * the time, and keeps the newest [HISTORY] of them: a wrong turn can be taken back days later.
     */
    private fun backUp(name: String) {
        val before = File(folder, name).takeIf { it.exists() }?.readBytes() ?: return
        val resolver = context.contentResolver
        val path = Environment.DIRECTORY_DOWNLOADS + "/CANLens/dbc/history/"
        val stem = name.removeSuffix(".dbc")
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$stem.${SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())}.dbc")
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, path)
        }) ?: return
        resolver.openOutputStream(uri)?.use { it.write(before) }
        // the names sort by time: all but the newest go
        val old = ArrayList<Pair<String, Long>>()
        val mine = Regex(Regex.escape(stem) + """\.\d{4}-\d\d-\d\d-\d{6}\.dbc""")   // LIKE alone would take another DBC's copies along
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf(path, "$stem.%.dbc"), null)?.use { rows ->
            while (rows.moveToNext()) if (mine.matches(rows.getString(1))) old += rows.getString(1) to rows.getLong(0)
        }
        for ((_, id) in old.sortedByDescending { it.first }.drop(HISTORY)) resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
    }

    /** The file outside the app that [name] is kept in: the one it was opened from, else a new one in `Download/CANLens/dbc/`. */
    private fun outside(name: String): Uri = preferences.getString("uri:$name", null)?.let(Uri::parse) ?: (context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CANLens/dbc")
    }) ?: throw IOException("can't create it in Downloads")).also { preferences.edit().putString("uri:$name", it.toString()).apply() }

    private companion object { const val HISTORY = 10 }
}
