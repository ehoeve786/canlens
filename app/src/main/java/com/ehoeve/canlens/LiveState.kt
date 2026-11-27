package com.ehoeve.canlens

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ehoeve.canlens.core.CandumpStream
import com.ehoeve.canlens.core.CommaFinder
import com.ehoeve.canlens.core.CommaStatus
import com.ehoeve.canlens.core.CommaStream
import com.ehoeve.canlens.core.FrameSink
import com.ehoeve.canlens.core.FrameStore
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class LiveSource { NONE, COMMA, DASHKIT, DASHKIT_WIFI }

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
    /** Whether a stream also carries the phone's sensors, as bus [PhoneSensors.PHONE_BUS]. */
    private val phoneSensorsState = mutableStateOf(preferences.getBoolean("phoneSensors", true))
    var phoneSensors: Boolean
        get() = phoneSensorsState.value
        set(value) { phoneSensorsState.value = value; preferences.edit().putBoolean("phoneSensors", value).apply() }

    /** The last recording of the stream that is, or was last, running: what a session can reopen of it. */
    var recorded: Uri? = null

    /** The DashKit on USB while that is the live source: the way to ask it to transmit. */
    @Volatile var dashKit: DashKitUsb? = null; private set
    /** The DashKit on Wi-Fi while that is the live source: its log and metrics. */
    @Volatile var dashKitWifi: DashKitWifi? = null; private set
    /** The comma's own stats and log while it is the live source (from its bridge, when that publishes them). */
    @Volatile var comma: CommaStatus? = null; private set

    private var job: Job? = null
    private var connection: Closeable? = null
    private var sensors: PhoneSensors? = null
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
        start(LiveSource.COMMA, "comma") { sink, mine ->
            val host = findComma(typed.substringBefore(':'), port)
            // the DashKit's chassis bus, from dashkitd on the comma, as bus 3; a comma without it just has no bus 3.
            // It is connected again while the comma's stream runs: it ends after a quiet spell, and the car's bus may sleep.
            val done = AtomicBoolean()
            val chassis = AtomicReference<CandumpStream?>()
            thread(isDaemon = true) {
                while (!done.get()) {
                    val stream = CandumpStream(host)
                    chassis.set(stream)
                    if (done.get()) { stream.close(); break }
                    runCatching { stream.run(sink) }
                    if (!done.get()) runCatching { Thread.sleep(2000) }
                }
            }
            // its stats and log, a connection per service; a bridge that doesn't publish them just leaves them empty
            val status = CommaStatus().also { s -> s.onLine = { recorder?.note("comma", it) } }
            comma = status
            val services = CommaStatus.SERVICES.map { service ->
                val current = AtomicReference<CommaStream?>()
                thread(isDaemon = true) {
                    while (!done.get()) {
                        val stream = CommaStream(host, CommaStream.cerealPort(service), 60_000)
                        current.set(stream)
                        if (done.get()) { stream.close(); break }
                        runCatching { stream.run(status::read) }
                        if (!done.get()) runCatching { Thread.sleep(10_000) }   // a bridge without it: no busy retrying
                    }
                }
                current
            }
            try { CommaStream(host, port).held(mine).use { it.run(sink) } } finally {
                done.set(true); chassis.get()?.close(); services.forEach { it.get()?.close() }
                if (comma === status) comma = null
            }
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

    fun startDashKit() = start(LiveSource.DASHKIT, "DashKit USB") { sink, mine ->
        // published as the transmit link only once the port is ours, and taken back when the stream ends
        DashKitUsb(context).held(mine).use { usb ->
            usb.status.onLine = { recorder?.note("dashkit", it) }
            try { usb.run(sink) { if (mine == generation) dashKit = usb } } finally { if (dashKit === usb) dashKit = null }
        }
    }

    /** The key that lets this app, and nothing else on the network, install firmware over Wi-Fi; made once, sent with the Wi-Fi settings. */
    fun wifiKey(): String = preferences.getString("wifiKey", null) ?: ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }.also { preferences.edit().putString("wifiKey", it).apply() }

    /**
     * Installs the firmware in [uri] into the DashKit's other OTA slot, as DashPilot does over Bluetooth (it goes back
     * to the old one if the new one doesn't start): over USB, else over Wi-Fi. Ends a stream first.
     */
    fun flash(uri: Uri, usb: Boolean) {
        stop()
        val how = if (usb) "USB" else "Wi-Fi"
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val image = (context.contentResolver.openInputStream(uri) ?: throw IOException("can't read the file")).use { it.readBytes() }
                // an ESP32 app image starts with 0xE9: anything else is the wrong file (a zip, an APK)
                if (image.isEmpty() || image[0] != 0xE9.toByte()) throw IOException("that isn't a firmware image (.bin)")
                val progress = { percent: Int -> scope.launch { report("Installing over $how: $percent %") }; Unit }
                if (usb) DashKitUsb(context).flash(image, progress) else flashOverWifi(image, progress)
            }
            // queued like the progress reports, so it comes after the last of them
            scope.launch {
                report(result.fold({ if (it == "O,ok") "Firmware installed over $how: the DashKit is restarting" else "Not installed: ${it.removePrefix("O,error ")}" }, { "Not installed: ${it.message}" }))
            }
        }
    }

    private fun flashOverWifi(image: ByteArray, progress: (Int) -> Unit): String {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(findDashKit(), DashKitWifi.PORT), 5000)
            socket.soTimeout = 30_000
            val out = socket.getOutputStream()
            out.write("O,${image.size},${wifiKey()}\n".toByteArray())
            var told = 0
            for (at in image.indices step 16384) {
                out.write(image, at, minOf(16384, image.size - at))
                val percent = (at + minOf(16384, image.size - at)) * 100 / image.size
                if (percent >= told + 10) { told = percent; progress(percent) }
            }
            out.flush()
            // the log and metrics come on the same connection: the answer is the "O" line among them
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val until = System.currentTimeMillis() + 30_000   // its metrics come every second: a time limit, not a silence one
            return generateSequence { if (System.currentTimeMillis() < until) reader.readLine() else null }.firstOrNull { it.startsWith("O,") }
                ?: throw IOException("this DashKit's firmware can't install over Wi-Fi yet: install the wifi-stream build with DashPilot once")
        }
    }

    /** Restarts the DashKit on USB (its reset line), ending a USB stream first: the port is needed for it. */
    fun restartDashKit() {
        if (source == LiveSource.DASHKIT) stop()
        scope.launch(Dispatchers.IO) {
            val problem = DashKitUsb(context).restart()
            withContext(Dispatchers.Main) { report(problem?.let { "Restart: $it" } ?: "The DashKit is restarting: it's back in a few seconds") }
        }
    }

    /** Over the phone's hotspot: at the address it had last time, else looked for there. */
    fun startDashKitWifi() = start(LiveSource.DASHKIT_WIFI, "DashKit Wi-Fi") { sink, mine ->
        DashKitWifi(findDashKit()).held(mine).use { wifi ->
            wifi.status.onLine = { recorder?.note("dashkit", it) }
            dashKitWifi = wifi
            try { wifi.run(sink) } finally { if (dashKitWifi === wifi) dashKitWifi = null }
        }
    }

    /**
     * Makes this the connection of stream [mine], for [stop] to close; when that stream was stopped meanwhile (a
     * thread of an old stream still connecting), closes it instead and ends that thread.
     */
    private fun <T : Closeable> T.held(mine: Int): T {
        synchronized(this@LiveState) { if (mine == generation) { connection = this; return this } }
        runCatching { close() }
        throw IOException("stopped")
    }

    private fun findDashKit(): String {
        val last = preferences.getString("dashKitWifi", "").orEmpty()
        val known = last.takeIf { it.isNotEmpty() }?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        if (known != null && CommaFinder.accepts(known, DashKitWifi.PORT)) return last
        scope.launch { report("Looking for the DashKit on this phone's hotspot") }
        val found = CommaFinder.find(CommaFinder.neighbours(), DashKitWifi.PORT, CommaFinder::accepts)?.hostAddress
            ?: throw IOException("no DashKit found on this phone's Wi-Fi or hotspot (is its Wi-Fi on?)")
        preferences.edit().putString("dashKitWifi", found).apply()
        return found
    }

    private fun start(from: LiveSource, name: String, connect: (FrameSink, Int) -> Unit) {
        stop()
        // under the store's lock, so a frame of the previous stream can't land after the clear
        val mine = synchronized(store) { store.clear(); ++generation }
        store.dropOldest = true
        recorded = null
        onStart()
        source = from
        LiveService.title = { (if (recording != null) "Recording · " else "Live · ") + name }
        LiveService.status = { "${store.frameCount} frames, ${store.messages.size} messages" + (recording?.let { " · Download/CANLens/$it" } ?: "") }
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
        // stamped with the stream's clock: the time of its latest frame, so they line up with the car's
        if (phoneSensors) sensors = PhoneSensors(context) { id, data ->
            synchronized(store) {
                if (mine == generation && store.frameCount > 0) {
                    recorder?.write(PhoneSensors.PHONE_BUS, id, data, 0, data.size)
                    store.frame(store.originMicros + store.lastTime * FrameStore.MICROS_PER_TICK, PhoneSensors.PHONE_BUS, id, data, 0, data.size)
                }
            }
        }.also { it.start() }
        job = scope.launch {
            launch(Dispatchers.IO) {
                while (isActive) {
                    val problem = try { connect(sink, mine); "stream ended" } catch (e: AccessRefused) {
                        // asking again every 2 s would pester the user: a refusal ends the stream
                        withContext(Dispatchers.Main) { if (isActive) { stop(); report("$name: ${e.message}. Stopped") } }
                        break
                    } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
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
                    report("$name: $count frames, ${store.messages.size} messages" + (recording?.let { "\nRecording to Download/CANLens/$it" } ?: ""))
                    seen = count
                    onFrames()
                }
            }
        }
    }

    fun stop() {
        val old = synchronized(this) { generation++; connection.also { connection = null } }
        job?.cancel(); job = null
        sensors?.stop(); sensors = null
        runCatching { old?.close() }; dashKit = null; dashKitWifi = null
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
            // a thread of its own, not [scope]: from onCleared the scope is already cancelled, and the log's end would be lost
            if (old != null) thread(name = "recorder-close") { runCatching { old.close() } }
        }
    }

    /** The recording's clock now, or null when not recording. */
    fun now(): Long? = recorder?.now()

    /** Writes a marker into the recording, if there is one. */
    fun mark(fileMicros: Long, note: String) { recorder?.marker(fileMicros, note) }
}
