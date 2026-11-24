package com.ehoeve.canpilot

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ehoeve.canpilot.core.CandumpStream
import com.ehoeve.canpilot.core.CommaFinder
import com.ehoeve.canpilot.core.CommaStream
import com.ehoeve.canpilot.core.FrameSink
import com.ehoeve.canpilot.core.FrameStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import kotlin.concurrent.thread

enum class LiveSource { NONE, COMMA, DASHKIT }

/**
 * The live stream into [store] and its recording. [onStart] runs when a stream begins, after the store was emptied;
 * [onFrames] a few times a second while frames arrive.
 */
class LiveState(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val scope: CoroutineScope,
    private val store: FrameStore,
    private val report: (String) -> Unit,
    private val onStart: () -> Unit,
    private val onFrames: () -> Unit,
) {
    var source by mutableStateOf(LiveSource.NONE); private set
    var recording by mutableStateOf<String?>(null); private set
    var commaAddress by mutableStateOf(preferences.getString("comma", "") ?: ""); private set

    /** The last recording of the stream that is, or was last, running: what a session can reopen of it. */
    var recorded: Uri? = null

    /** The DashKit on USB while that is the live source: the way to ask it to transmit. */
    @Volatile var dashKit: DashKitUsb? = null; private set

    private var job: Job? = null
    private var connection: Closeable? = null
    @Volatile private var recorder: Recorder? = null
    /** Changes with every stream start and stop, so a stream that is still shutting down can't write into the next one's data. */
    @Volatile private var generation = 0

    /**
     * [address] is where the comma was last, optionally with `:port` when its `can` service isn't on the default port;
     * it may be empty. Each time the stream (re)connects, a comma that no longer answers there is looked for on the
     * phone's Wi-Fi and hotspot, and the address it is found at is kept for the next time.
     */
    fun startComma(address: String) {
        val typed = address.trim()
        val port = typed.substringAfter(':', "").trim().toIntOrNull() ?: CommaStream.cerealPort("can")
        start(LiveSource.COMMA, "comma") { sink ->
            val host = findComma(typed.substringBefore(':'), port)
            // the DashKit's chassis bus, from dashkitd on the comma, as bus 3; a comma without it just has no bus 3
            val chassis = CandumpStream(host)
            thread(isDaemon = true) { runCatching { chassis.run(sink) } }
            try { CommaStream(host, port).also { connection = it }.use { it.run(sink) } } finally { chassis.close() }
        }
    }

    private fun findComma(last: String, port: Int): String {
        val known = last.takeIf { it.isNotEmpty() }?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        if (known != null && CommaFinder.publishes(known, port)) return last
        scope.launch { report("Looking for the comma on this phone's Wi-Fi and hotspot") }
        val found = CommaFinder.find(CommaFinder.neighbours(), port)?.hostAddress ?: throw IOException("no comma found on this phone's Wi-Fi or hotspot (is its bridge running?)")
        val saved = if (port == CommaStream.cerealPort("can")) found else "$found:$port"
        preferences.edit().putString("comma", saved).apply()
        scope.launch { commaAddress = saved; report("Found the comma at $found") }
        return found
    }

    fun startDashKit() = start(LiveSource.DASHKIT, "DashKit USB") { sink ->
        DashKitUsb(context).also { connection = it; dashKit = it }.use { it.run(sink) }
    }

    private fun start(from: LiveSource, name: String, connect: (FrameSink) -> Unit) {
        stop()
        // under the store's lock, so a frame of the previous stream can't land after the clear
        val mine = synchronized(store) { store.clear(); ++generation }
        store.dropOldest = true
        recorded = null
        onStart()
        source = from
        LiveService.title = { (if (recording != null) "Recording · " else "Live · ") + name }
        LiveService.status = { "${store.frameCount} frames, ${store.messages.size} messages" + (recording?.let { " · Download/CanPilot/$it" } ?: "") }
        LiveService.stop = { stop() }
        LiveService.begin(context)
        report("Connecting to $name")
        val sink = FrameSink { time, bus, id, data, offset, length ->
            synchronized(store) {
                if (mine != generation) return@FrameSink false
                recorder?.write(bus, id, data, offset, length)
                store.frame(time, bus, id, data, offset, length)
            }
        }
        job = scope.launch {
            launch(Dispatchers.IO) {
                while (isActive) {
                    val problem = try { connect(sink); "stream ended" } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
                    if (!isActive) break
                    withContext(Dispatchers.Main) { report("$name: $problem. Retrying") }
                    delay(2000)
                }
            }
            var seen = 0L
            while (isActive) {
                delay(250)
                val count = store.frameCount
                if (count != seen) {
                    report("$name: $count frames, ${store.messages.size} messages" + (recording?.let { "\nRecording to Download/CanPilot/$it" } ?: ""))
                    seen = count
                    onFrames()
                }
            }
        }
    }

    fun stop() {
        generation++
        job?.cancel(); job = null
        runCatching { connection?.close() }; connection = null; dashKit = null
        setRecording(false)
        if (source != LiveSource.NONE) {
            source = LiveSource.NONE
            LiveService.stop = null; LiveService.status = null; LiveService.title = null
            LiveService.end(context)
            report("Stopped: ${store.frameCount} frames kept")
        }
    }

    fun setRecording(on: Boolean) {
        if (on == (recorder != null)) return
        if (on) {
            try { recorder = Recorder(context).also { recording = it.name; recorded = it.uri } } catch (e: Exception) { report("Recording failed: ${e.message}") }
        } else {
            val old = recorder
            recorder = null; recording = null
            scope.launch(Dispatchers.IO) { runCatching { old?.close() } }
        }
    }

    /** The recording's clock now, or null when not recording. */
    fun now(): Long? = recorder?.now()

    /** Writes a marker into the recording, if there is one. */
    fun mark(fileMicros: Long, note: String) { recorder?.marker(fileMicros, note) }
}
