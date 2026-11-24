package com.ehoeve.canpilot

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ehoeve.canpilot.core.CommaStream
import com.ehoeve.canpilot.core.Dbc
import com.ehoeve.canpilot.core.DbcMessage
import com.ehoeve.canpilot.core.FrameSink
import com.ehoeve.canpilot.core.FrameStore
import com.ehoeve.canpilot.core.LogReader
import com.ehoeve.canpilot.core.Signal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException

/** A DBC in use and the bus it decodes ([ALL_BUSES] for every bus). Kept as a private copy in the app's files. */
class LoadedDbc(val dbc: Dbc, val bus: Int)

/** A plotted signal. Plots stay up while moving between messages, so signals of different messages can be compared. */
data class PlotKey(val bus: Int, val id: Int, val signal: String)

const val ALL_BUSES = -1

enum class LiveSource { NONE, COMMA, DASHKIT }

class AppModel(application: Application) : AndroidViewModel(application) {
    private val context: Context get() = getApplication()
    private val preferences = application.getSharedPreferences("canpilot", Context.MODE_PRIVATE)
    private val dbcFolder = File(application.filesDir, "dbc").apply { mkdirs() }

    val store = FrameStore()
    val dbcs = mutableStateListOf<LoadedDbc>()
    val plots = mutableStateListOf<PlotKey>()
    /** The time range all plots show; replaced when other data is loaded. */
    var chartView by mutableStateOf(ChartView(0f, 1f)); private set

    /** Goes up whenever the store's content changed; composables read it to refresh. */
    var revision by mutableIntStateOf(0); private set
    var status by mutableStateOf("Open a log or start a live stream"); private set
    var busy by mutableStateOf(false); private set
    var live by mutableStateOf(LiveSource.NONE); private set
    var recording by mutableStateOf<String?>(null); private set
    var commaAddress by mutableStateOf(preferences.getString("comma", "") ?: ""); private set

    /** Cursor and the A/B marks of the comparison window, in store time (100 µs units); marks are null when unset. */
    var cursor by mutableIntStateOf(0)
    var markA by mutableStateOf<Int?>(null)
    var markB by mutableStateOf<Int?>(null)
    /** While a live stream runs: the cursor stays on the newest frame until the user moves it. */
    var followLive by mutableStateOf(false)
    val cursorNow: Int get() = if (followLive && live != LiveSource.NONE) store.lastTime else cursor

    private var liveJob: Job? = null
    private var liveConnection: Closeable? = null
    @Volatile private var recorder: Recorder? = null
    /** Changes with every stream start and stop, so a stream that is still shutting down can't write into the next one's data. */
    @Volatile private var liveGeneration = 0

    init {
        for (file in dbcFolder.listFiles().orEmpty().sortedBy { it.name }) {
            runCatching { dbcs += LoadedDbc(Dbc.parse(file.readText(), file.name), preferences.getInt("bus:" + file.name, ALL_BUSES)) }
        }
    }

    /** The DBC message for a frame: a DBC assigned to that bus wins over one for all buses. */
    fun decoder(bus: Int, id: Int): DbcMessage? =
        dbcs.firstOrNull { it.bus == bus && id in it.dbc.messages }?.dbc?.messages?.get(id)
            ?: dbcs.firstOrNull { it.bus == ALL_BUSES }?.dbc?.messages?.get(id)

    // ---- logs

    fun openLogs(uris: List<Uri>) {
        if (uris.isEmpty() || busy) return
        stopLive()
        busy = true
        viewModelScope.launch {
            val problems = ArrayList<String>()
            withContext(Dispatchers.IO) {
                store.clear()
                store.dropOldest = false
                // route segments in order: natural sort puts --2-- before --10--
                val files = uris.map { context.displayName(it) to it }.sortedWith { a, b -> naturalCompare(a.first, b.first) }
                for ((index, file) in files.withIndex()) {
                    withContext(Dispatchers.Main) { status = "Reading ${file.first} (${index + 1}/${files.size})" }
                    val before = store.frameCount
                    try {
                        context.openDecompressed(file.second).use { LogReader.read(it, store) }
                        if (store.frameCount == before) problems += "${file.first}: no CAN frames"
                    } catch (e: Exception) {
                        problems += "${file.first}: ${e.message ?: e.javaClass.simpleName}"
                    } catch (e: OutOfMemoryError) {
                        problems += "${file.first}: out of memory"
                    }
                    if (store.truncated) { problems += "memory limit reached, the rest was not loaded"; break }
                }
            }
            cursor = store.firstTime; markA = null; markB = null; followLive = false
            chartView = ChartView(seconds(store.firstTime), seconds(store.lastTime))
            status = "${store.frameCount} frames, ${store.messages.size} messages, ${"%.1f".format(store.lastTime / FrameStore.TICKS_PER_SECOND)} s" +
                if (problems.isEmpty()) "" else "\n" + problems.joinToString("\n")
            busy = false
            revision++
        }
    }

    // ---- DBCs

    fun openDbc(uri: Uri) {
        viewModelScope.launch {
            try {
                val name = context.displayName(uri).replace('/', '_')
                val text = withContext(Dispatchers.IO) { (context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open $name")).use { it.readBytes().toString(Charsets.ISO_8859_1) } }
                val dbc = Dbc.parse(text, name)
                if (dbc.messages.isEmpty()) throw IOException("$name has no messages")
                withContext(Dispatchers.IO) { File(dbcFolder, name).writeText(text, Charsets.ISO_8859_1) }
                dbcs.removeAll { it.dbc.name == name }
                dbcs += LoadedDbc(dbc, preferences.getInt("bus:$name", ALL_BUSES))
            } catch (e: Exception) {
                status = "DBC: ${e.message}"
            }
        }
    }

    fun setDbcBus(index: Int, bus: Int) {
        val old = dbcs[index]
        dbcs[index] = LoadedDbc(old.dbc, bus)
        preferences.edit().putInt("bus:" + old.dbc.name, bus).apply()
    }

    fun removeDbc(index: Int) {
        val old = dbcs.removeAt(index)
        File(dbcFolder, old.dbc.name).delete()
        preferences.edit().remove("bus:" + old.dbc.name).apply()
    }

    fun exportDbc(index: Int, uri: Uri) {
        val text = dbcs[index].dbc.write()
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("can't write")).use { it.write(text.toByteArray(Charsets.ISO_8859_1)) } }
            withContext(Dispatchers.Main) { status = result.fold({ "DBC saved" }, { "DBC not saved: ${it.message}" }) }
        }
    }

    /** Adds or replaces a signal in the DBC that decodes [message], or in a new DBC for its bus if none does. */
    fun saveSignal(message: FrameStore.Message, signal: Signal, replaces: String?) {
        val index = dbcIndex(message)
        val old = if (index >= 0) dbcs[index] else LoadedDbc(Dbc("canpilot-bus${message.bus}.dbc", emptyMap()), message.bus)
        var dbc = old.dbc
        if (replaces != null && replaces != signal.name) dbc = dbc.withoutSignal(message.id, replaces)
        dbc = dbc.withSignal(message.id, "NEW_%X".format(message.id), message.data.size, signal)
        if (replaces != null && replaces != signal.name) {
            val at = plots.indexOf(PlotKey(message.bus, message.id, replaces))
            if (at >= 0) plots[at] = PlotKey(message.bus, message.id, signal.name)
        }
        replaceDbc(index, LoadedDbc(dbc, old.bus))
    }

    fun deleteSignal(message: FrameStore.Message, name: String) {
        val index = dbcIndex(message)
        plots.remove(PlotKey(message.bus, message.id, name))
        if (index >= 0) replaceDbc(index, LoadedDbc(dbcs[index].dbc.withoutSignal(message.id, name), dbcs[index].bus))
    }

    private fun dbcIndex(message: FrameStore.Message): Int {
        val exact = dbcs.indexOfFirst { it.bus == message.bus && message.id in it.dbc.messages }
        if (exact >= 0) return exact
        val shared = dbcs.indexOfFirst { it.bus == ALL_BUSES && message.id in it.dbc.messages }
        if (shared >= 0) return shared
        return dbcs.indexOfFirst { it.bus == message.bus }.takeIf { it >= 0 } ?: dbcs.indexOfFirst { it.bus == ALL_BUSES }
    }

    private fun replaceDbc(index: Int, value: LoadedDbc) {
        if (index >= 0) dbcs[index] = value else dbcs += value
        preferences.edit().putInt("bus:" + value.dbc.name, value.bus).apply()
        val text = value.dbc.write()
        viewModelScope.launch(Dispatchers.IO) { runCatching { File(dbcFolder, value.dbc.name).writeText(text, Charsets.ISO_8859_1) } }
    }

    // ---- live streams

    /** [address] is the comma device's IP, optionally with `:port` when its `can` service isn't on the default port. */
    fun startComma(address: String) {
        val host = address.trim().substringBefore(':')
        val port = address.substringAfter(':', "").trim().toIntOrNull() ?: CommaStream.cerealPort("can")
        if (host.isEmpty()) { status = "Enter the comma device's IP address"; return }
        commaAddress = address.trim()
        preferences.edit().putString("comma", commaAddress).apply()
        startLive(LiveSource.COMMA, "comma $host") { sink ->
            CommaStream(host, port).also { liveConnection = it }.use { it.run(sink) }
        }
    }

    fun startDashKit() = startLive(LiveSource.DASHKIT, "DashKit USB") { sink ->
        DashKitUsb(context).also { liveConnection = it }.use { it.run(sink) }
    }

    private fun startLive(source: LiveSource, name: String, connect: (FrameSink) -> Unit) {
        stopLive()
        // under the store's lock, so a frame of the previous stream can't land after the clear
        val generation = synchronized(store) { store.clear(); ++liveGeneration }
        store.dropOldest = true
        cursor = 0; markA = null; markB = null; followLive = true
        chartView = ChartView(0f, LIVE_WINDOW_SECONDS)
        live = source
        status = "Connecting to $name"
        val sink = FrameSink { time, bus, id, data, offset, length ->
            synchronized(store) {
                if (generation != liveGeneration) return@FrameSink false
                recorder?.write(bus, id, data, offset, length)
                store.frame(time, bus, id, data, offset, length)
            }
        }
        liveJob = viewModelScope.launch {
            launch(Dispatchers.IO) {
                while (isActive) {
                    val problem = try { connect(sink); "stream ended" } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
                    if (!isActive) break
                    withContext(Dispatchers.Main) { status = "$name: $problem. Retrying" }
                    delay(2000)
                }
            }
            var seen = 0L
            while (isActive) {
                delay(250)
                val count = store.frameCount
                if (count != seen) {
                    status = "$name: $count frames, ${store.messages.size} messages" + (recording?.let { "\nRecording to Download/CanPilot/$it" } ?: "")
                    seen = count
                    revision++
                }
            }
        }
    }

    fun stopLive() {
        liveGeneration++
        liveJob?.cancel(); liveJob = null
        runCatching { liveConnection?.close() }; liveConnection = null
        setRecording(false)
        if (live != LiveSource.NONE) { live = LiveSource.NONE; status = "Stopped: ${store.frameCount} frames kept" }
    }

    fun setRecording(on: Boolean) {
        if (on == (recorder != null)) return
        if (on) {
            try { recorder = Recorder(context).also { recording = it.name } } catch (e: Exception) { status = "Recording failed: ${e.message}" }
        } else {
            val old = recorder
            recorder = null; recording = null
            viewModelScope.launch(Dispatchers.IO) { runCatching { old?.close() } }
        }
    }

    override fun onCleared() = stopLive()

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
