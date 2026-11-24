package com.ehoeve.canpilot

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import com.ehoeve.canpilot.core.Dbc
import com.ehoeve.canpilot.core.DbcMessage
import com.ehoeve.canpilot.core.FrameStore
import com.ehoeve.canpilot.core.Signal
import com.ehoeve.canpilot.core.Suggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A DBC in use and the bus it decodes ([ALL_BUSES] for every bus). Kept as a private copy in the app's files. */
class LoadedDbc(val dbc: Dbc, val bus: Int)

const val ALL_BUSES = -1

/** The DBC files in use, and every change to them. [plots] follows when a plotted signal is renamed or deleted. */
class DbcState(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val scope: CoroutineScope,
    private val plots: MutableList<PlotKey>,
    private val report: (String) -> Unit,
) {
    private val folder = File(context.filesDir, "dbc").apply { mkdirs() }

    val files = mutableStateListOf<LoadedDbc>()

    init {
        for (file in folder.listFiles().orEmpty().sortedBy { it.name }) {
            runCatching { files += LoadedDbc(Dbc.parse(file.readText(), file.name), preferences.getInt("bus:" + file.name, ALL_BUSES)) }
        }
    }

    /** The DBC message for a frame: a DBC assigned to that bus wins over one for all buses. */
    fun decoder(bus: Int, id: Int): DbcMessage? =
        files.firstOrNull { it.bus == bus && id in it.dbc.messages }?.dbc?.messages?.get(id)
            ?: files.firstOrNull { it.bus == ALL_BUSES }?.dbc?.messages?.get(id)

    fun open(uri: Uri) {
        scope.launch {
            try {
                val name = context.displayName(uri).replace('/', '_')
                val text = withContext(Dispatchers.IO) { (context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open $name")).use { it.readBytes().toString(Charsets.ISO_8859_1) } }
                val dbc = Dbc.parse(text, name)
                if (dbc.messages.isEmpty()) throw IOException("$name has no messages")
                withContext(Dispatchers.IO) { File(folder, name).writeText(text, Charsets.ISO_8859_1) }
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
        preferences.edit().remove("bus:" + old.dbc.name).apply()
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
        val old = if (index >= 0) files[index] else LoadedDbc(Dbc("canpilot-bus${message.bus}.dbc", emptyMap()), message.bus)
        var dbc = old.dbc
        if (replaces != null && replaces != signal.name) dbc = dbc.withoutSignal(message.id, replaces)
        dbc = dbc.withSignal(message.id, "NEW_%X".format(message.id), message.data.size, signal)
        if (replaces != null && replaces != signal.name) {
            val at = plots.indexOf(PlotKey(message.bus, message.id, replaces))
            if (at >= 0) plots[at] = PlotKey(message.bus, message.id, signal.name)
        }
        replace(index, LoadedDbc(dbc, old.bus))
    }

    fun deleteSignal(message: FrameStore.Message, name: String) {
        val index = indexFor(files, message)
        plots.remove(PlotKey(message.bus, message.id, name))
        if (index >= 0) replace(index, LoadedDbc(files[index].dbc.withoutSignal(message.id, name), files[index].bus))
    }

    /** Adds every suggested signal to the DBC its message belongs in; each DBC file is written once. */
    fun add(suggestions: List<Suggestion>) {
        val working = files.toMutableList()
        val changed = LinkedHashSet<Int>()
        for (suggestion in suggestions) {
            val message = suggestion.message
            var index = indexFor(working, message)
            if (index < 0) { working += LoadedDbc(Dbc("canpilot-bus${message.bus}.dbc", emptyMap()), message.bus); index = working.lastIndex }
            working[index] = LoadedDbc(working[index].dbc.withSignal(message.id, "NEW_%X".format(message.id), message.data.size, suggestion.signal), working[index].bus)
            changed += index
        }
        val existing = files.size
        for (index in changed) replace(if (index < existing) index else -1, working[index])
    }

    /** The DBC in [dbcs] a signal of [message] belongs in, or -1: one that has the message, else one for its bus, else one for all buses. */
    private fun indexFor(dbcs: List<LoadedDbc>, message: FrameStore.Message): Int {
        val exact = dbcs.indexOfFirst { it.bus == message.bus && message.id in it.dbc.messages }
        if (exact >= 0) return exact
        val shared = dbcs.indexOfFirst { it.bus == ALL_BUSES && message.id in it.dbc.messages }
        if (shared >= 0) return shared
        return dbcs.indexOfFirst { it.bus == message.bus }.takeIf { it >= 0 } ?: dbcs.indexOfFirst { it.bus == ALL_BUSES }
    }

    private fun replace(index: Int, value: LoadedDbc) {
        if (index >= 0) files[index] = value else files += value
        preferences.edit().putInt("bus:" + value.dbc.name, value.bus).apply()
        val text = value.dbc.write()
        scope.launch(Dispatchers.IO) { runCatching { File(folder, value.dbc.name).writeText(text, Charsets.ISO_8859_1) } }
    }
}
