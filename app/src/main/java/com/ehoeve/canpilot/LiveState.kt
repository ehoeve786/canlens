package com.ehoeve.canpilot

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    private var job: Job? = null
    private var connection: Closeable? = null
    @Volatile private var recorder: Recorder? = null
    /** Changes with every stream start and stop, so a stream that is still shutting down can't write into the next one's data. */
    @Volatile private var generation = 0

    /** [address] is the comma device's IP, optionally with `:port` when its `can` service isn't on the default port. */
    fun startComma(address: String) {
        val host = address.trim().substringBefore(':')
        val port = address.substringAfter(':', "").trim().toIntOrNull() ?: CommaStream.cerealPort("can")
        if (host.isEmpty()) { report("Enter the comma device's IP address"); return }
        commaAddress = address.trim()
        preferences.edit().putString("comma", commaAddress).apply()
        start(LiveSource.COMMA, "comma $host") { sink ->
            CommaStream(host, port).also { connection = it }.use { it.run(sink) }
        }
    }

    fun startDashKit() = start(LiveSource.DASHKIT, "DashKit USB") { sink ->
        DashKitUsb(context).also { connection = it }.use { it.run(sink) }
    }

    private fun start(from: LiveSource, name: String, connect: (FrameSink) -> Unit) {
        stop()
        // under the store's lock, so a frame of the previous stream can't land after the clear
        val mine = synchronized(store) { store.clear(); ++generation }
        store.dropOldest = true
        recorded = null
        onStart()
        source = from
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
        runCatching { connection?.close() }; connection = null
        setRecording(false)
        if (source != LiveSource.NONE) { source = LiveSource.NONE; report("Stopped: ${store.frameCount} frames kept") }
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
