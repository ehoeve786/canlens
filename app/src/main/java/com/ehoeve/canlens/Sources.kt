package com.ehoeve.canlens

import java.util.concurrent.ConcurrentLinkedQueue
import com.ehoeve.canlens.core.DashKitStatus
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.ehoeve.canlens.core.DashKitLine
import com.ehoeve.canlens.core.DeviceClock
import com.ehoeve.canlens.core.FrameSink
import com.ehoeve.canlens.core.appendCandump
import com.ehoeve.canlens.core.appendMarker
import com.ehoeve.canlens.core.appendNote
import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.Writer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.nio.charset.Charset

fun Context.displayName(uri: Uri): String =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: uri.lastPathSegment ?: "file"

/** The ending of the file [writeWhole] writes first; one left by a kill mid-write is not a file of the app's. */
const val PART_FILE = ".part"

/**
 * Writes [text] to this file whole or not at all: to a file beside it first, flushed to the disk, then renamed over
 * it. A kill or a flat battery mid-write leaves the old file as it was instead of a cut-off one.
 */
fun File.writeWhole(text: String, charset: Charset = Charsets.UTF_8) {
    val part = File(parentFile, name + PART_FILE)
    try {
        FileOutputStream(part).use { it.write(text.toByteArray(charset)); it.fd.sync() }
        if (!part.renameTo(this)) throw IOException("can't replace $name")
    } catch (e: IOException) {
        part.delete()
        throw e
    }
}

/** The file's content, decompressed if it is zstd (rlog.zst), bzip2 (rlog.bz2) or gzip. */
fun Context.openDecompressed(uri: Uri): InputStream {
    val raw = BufferedInputStream(contentResolver.openInputStream(uri) ?: throw IOException("Can't open ${displayName(uri)}"), 1 shl 16)
    // the decompressors read a header at once: one that throws leaves [raw] for us to close
    return try {
        raw.mark(4)
        val magic = ByteArray(4)
        val n = raw.read(magic)
        raw.reset()
        fun at(i: Int) = if (i < n) magic[i].toInt() and 0xFF else -1
        when {
            at(0) == 0x28 && at(1) == 0xB5 && at(2) == 0x2F && at(3) == 0xFD -> ZstdInputStream(raw).setContinuous(true)
            at(0) == 'B'.code && at(1) == 'Z'.code && at(2) == 'h'.code -> BZip2CompressorInputStream(raw, true)
            at(0) == 0x1F && at(1) == 0x8B -> GZIPInputStream(raw)
            else -> raw
        }
    } catch (e: Exception) {
        raw.close()
        throw e
    }
}

/** The user would not let the app at a device: retrying would only ask again, so a live stream ends on it. */
class AccessRefused(message: String) : IOException(message)

/** Monotonic microseconds that start at the wall clock: a clock change mid-stream can't reorder frames. */
class Clock {
    private val wallMicros = System.currentTimeMillis() * 1000
    private val startNanos = SystemClock.elapsedRealtimeNanos()
    fun micros() = wallMicros + (SystemClock.elapsedRealtimeNanos() - startNanos) / 1000
}

/** Writes a live stream to `Download/CANLens/can-log-<time>.log.zst` in candump format, zstd-compressed, stamped with the phone's clock. */
class Recorder(context: Context) : Closeable {
    val name = "can-log-" + SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date()) + ".log.zst"
    private val clock = Clock()
    private val line = StringBuilder(160)
    val uri: Uri
    private val writer: Writer

    init {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            // octet-stream: Android keeps the .zst name instead of adding .txt
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CANLens")
        }
        uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Can't create $name in Downloads")
        // zstd as it is written: about a tenth of the size, and the reader takes it as it is, a cut-off end included
        writer = try {
            ZstdOutputStream(context.contentResolver.openOutputStream(uri) ?: throw IOException("Can't write $name")).bufferedWriter(Charsets.US_ASCII)
        } catch (e: Exception) {
            // no empty file left behind in Downloads
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw e
        }
    }

    private var closed = false

    /** The time a frame written now would get. */
    fun now() = clock.micros()

    /** Adds a note at [timeMicros] (from [now]); it is read back as a marker when the log is opened. */
    @Synchronized
    fun marker(timeMicros: Long, note: String) {
        if (closed) return
        line.setLength(0)
        line.appendMarker(timeMicros, note)
        try { writer.append(line) } catch (e: IOException) { closed = true }
    }

    /** What a device said of itself (stats, log lines), at the time it came. */
    @Synchronized
    fun note(source: String, text: String) {
        if (closed) return
        line.setLength(0)
        line.appendNote(clock.micros(), source, text)
        try { writer.append(line) } catch (e: IOException) { closed = true }
    }

    /** A failed write (storage full) ends the recording; it never disturbs the stream. */
    @Synchronized
    fun write(bus: Int, id: Int, data: ByteArray, offset: Int, length: Int) {
        if (closed) return
        line.setLength(0)
        line.appendCandump(clock.micros(), bus, id, data, offset, length)
        try { writer.append(line) } catch (e: IOException) { closed = true }
    }

    @Synchronized
    override fun close() { closed = true; writer.close() }
}

/**
 * The DashKit's USB-C port: the ESP32-S3's built-in USB serial (CDC), read with Android's USB host API.
 * No control-line requests are sent: on this chip DTR/RTS are the reset and boot-mode lines.
 */
class DashKitUsb(private val context: Context) : TxLink, Closeable {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var closed = false

    /** What the DashKit last reported about transmitting and its CAN controllers. */
    override val status = DashKitStatus()
    @Volatile override var armed = false
    private val outbox = ConcurrentLinkedQueue<ByteArray>()

    /**
     * Queues a line for the DashKit ([DashKitLine.transmit]); it goes out within a few milliseconds. Arming and
     * disarming go ahead of anything still waiting (dropped), and at most [MAX_WAITING] lines wait.
     */
    override fun send(line: String) {
        if (line == DashKitLine.ARM || line == DashKitLine.DISARM) outbox.clear() else if (outbox.size >= MAX_WAITING) return
        outbox += line.toByteArray(Charsets.US_ASCII)
    }

    fun find(): UsbDevice? = manager.deviceList.values.firstOrNull { it.vendorId == ESPRESSIF && it.productId == USB_SERIAL_JTAG }

    /** Asks the user for access to [device] if needed; blocks until answered. */
    private fun permitted(device: UsbDevice): Boolean {
        if (manager.hasPermission(device)) return true
        val answered = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = answered.countDown()
        }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            manager.requestPermission(device, intent)
            answered.await(60, TimeUnit.SECONDS)
        } finally {
            context.unregisterReceiver(receiver)
        }
        return manager.hasPermission(device)
    }

    /**
     * Restarts the DashKit by the chip's own reset line, whatever its firmware is doing: the ESP32-S3's USB serial
     * holds the chip in reset while RTS is set, and with DTR clear it starts the firmware, not the bootloader.
     * Blocks while USB access is asked for. Null when done, else why not.
     */
    fun restart(): String? {
        val device = find() ?: return "No DashKit on USB"
        if (!permitted(device)) return "USB access to the DashKit was not allowed"
        val control = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
            ?: return "The DashKit's USB port has no serial control interface"
        val connection = manager.openDevice(device) ?: return "Can't open the DashKit's USB port"
        try {
            connection.claimInterface(control, true)
            // CDC SET_CONTROL_LINE_STATE: RTS on (reset), then both off (run); the second may find it already gone
            if (connection.controlTransfer(0x21, 0x22, RTS, control.id, null, 0, 200) < 0) return "The DashKit didn't take the reset"
            Thread.sleep(100)
            connection.controlTransfer(0x21, 0x22, 0, control.id, null, 0, 200)
        } finally {
            connection.close()
        }
        return null
    }

    /**
     * Installs [image] through the USB port (the firmware's "O,<size>" command: written to its other OTA slot, then it
     * restarts). [progress] gets the percentage sent. Blocks; the firmware's answer, "O,ok" or "O,error <why>".
     */
    fun flash(image: ByteArray, progress: (Int) -> Unit): String {
        val device = find() ?: throw IOException("No DashKit on USB")
        if (!permitted(device)) throw AccessRefused("USB access to the DashKit was not allowed")
        val data = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
            ?: throw IOException("The USB device has no serial interface")
        val endpoints = (0 until data.endpointCount).map { data.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: throw IOException("No way to read from the DashKit")
        val output = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: throw IOException("No way to write to the DashKit")
        val connection = manager.openDevice(device) ?: throw IOException("Can't open the DashKit's USB port")
        try {
            if (!connection.claimInterface(data, true)) throw IOException("Can't claim the DashKit's serial interface")
            fun write(bytes: ByteArray, from: Int, count: Int) {
                var at = from
                while (at < from + count) {
                    val sent = connection.bulkTransfer(output, bytes, at, minOf(16384, from + count - at), 5000)
                    if (sent <= 0) throw IOException("The DashKit stopped taking the image")
                    at += sent
                }
            }
            val head = "O,${image.size}\n".toByteArray()
            write(head, 0, head.size)
            // its 1 KB USB buffer loses what comes faster than flash writes: send USB_CHUNK bytes per "K" line it sends,
            // then wait for the "O" line among its frame lines (the check after the last chunk takes a while)
            var sent = 0
            var told = 0
            val chunk = ByteArray(16384)
            val line = StringBuilder()
            var until = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < until) {
                val n = connection.bulkTransfer(input, chunk, chunk.size, 500)
                for (i in 0 until maxOf(n, 0)) {
                    val c = chunk[i].toInt().toChar()
                    if (c != '\n') { if (line.length < 200) line.append(c); continue }
                    val text = line.trimEnd('\r').toString()
                    line.setLength(0)
                    if (text.startsWith("O,")) return if (sent == 0 && "stopped" in text) "$text (this firmware can't install over USB yet: install it over Wi-Fi once)" else text
                    if (text != "K" || sent >= image.size) continue
                    val count = minOf(USB_CHUNK, image.size - sent)
                    write(image, sent, count)
                    sent += count
                    until = SystemClock.elapsedRealtime() + if (sent < image.size) 30_000 else 60_000
                    val percent = sent * 100L / image.size
                    if (percent >= told + 10) { told = percent.toInt(); progress(told) }
                }
            }
            throw IOException(if (sent == 0) "no answer: this DashKit's firmware can't install over USB yet: install it over Wi-Fi once" else "the DashKit stopped answering")
        } finally {
            connection.close()
        }
    }

    /** Reads frames into [sink] until [close], the sink returns false, or the cable is pulled (IOException); [ready] once the port is ours. */
    fun run(sink: FrameSink, ready: () -> Unit = {}) {
        val device = find() ?: throw IOException("No DashKit on USB")
        if (!permitted(device)) throw AccessRefused("USB access to the DashKit was not allowed")
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        val data = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
            ?: throw IOException("The USB device has no serial interface")
        for (i in 0 until data.endpointCount) {
            val e = data.getEndpoint(i)
            if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (e.direction == UsbConstants.USB_DIR_IN) input = e else output = e
        }
        val connection = manager.openDevice(device) ?: throw IOException("Can't open the DashKit's USB port")
        try {
            if (input == null || !connection.claimInterface(data, true)) throw IOException("Can't claim the DashKit's serial interface")
            ready()
            val clock = Clock()
            val busClock = DeviceClock()
            val chunk = ByteArray(16384)
            val line = StringBuilder(64)
            val payload = ByteArray(64)
            val request = DashKitLine.STREAM_ALL.toByteArray()
            val arm = DashKitLine.ARM.toByteArray()
            var lastRequest = 0L
            var silentSince = SystemClock.elapsedRealtime()
            while (!closed) {
                val now = SystemClock.elapsedRealtime()
                if (output != null && now - lastRequest >= 1000) {
                    connection.bulkTransfer(output, request, request.size, 100)
                    if (armed) connection.bulkTransfer(output, arm, arm.size, 100)
                    lastRequest = now
                }
                while (output != null) { val next = outbox.poll() ?: break; connection.bulkTransfer(output, next, next.size, 100) }
                // a short wait, so that a line to send never sits long behind a silent bus
                val n = connection.bulkTransfer(input, chunk, chunk.size, 20)
                if (n <= 0) {
                    // a timeout and a pulled cable both give -1: tell them apart by whether the device is still listed
                    if (now - silentSince > 1000 && find() == null) throw IOException("DashKit unplugged")
                    continue
                }
                silentSince = now
                val time = clock.micros()
                for (i in 0 until n) {
                    val c = chunk[i].toInt().toChar()
                    if (c == '\n') {
                        if (line.startsWith("A,") || line.startsWith("E,")) { if (!DashKitLine.parse(line.toString(), time, payload, sink, busClock)) return } else status.read(line.toString())
                        line.setLength(0)
                    } else if (c != '\r' && line.length < 200) line.append(c)
                }
            }
        } finally {
            connection.releaseInterface(data)
            connection.close()
        }
    }

    override fun close() { closed = true }

    companion object {
        const val ESPRESSIF = 0x303A
        const val USB_SERIAL_JTAG = 0x1001
        /** The RTS bit of CDC's control line state: the ESP32-S3's reset line. */
        private const val RTS = 2
        /** Install image bytes per "K" from the DashKit: half its USB buffer (firmware can_manager.c USB_CHUNK). */
        private const val USB_CHUNK = 512
        /** Transmit lines waiting for the USB port; more are dropped (the sender is told by the firmware's counts). */
        const val MAX_WAITING = 64
        private const val ACTION_PERMISSION = "com.ehoeve.canlens.USB_PERMISSION"
    }
}

/**
 * The DashKit's Wi-Fi link (wifi_link.h in the firmware): every frame of both buses like USB, plus the firmware log and
 * its metrics, over the phone's hotspot. It doesn't transmit: that stays on USB and Bluetooth.
 */
class DashKitWifi(private val host: String) : Closeable {
    private val socket = Socket()
    /** The frames come here as UDP datagrams (wifi_link.h): TCP would wait for acknowledgements the DashKit's Wi-Fi sleeps through. */
    private val datagrams = DatagramSocket(0).apply { receiveBufferSize = 1 shl 20 }
    val status = DashKitStatus()

    /** Reads frames into [sink] until [close], the sink returns false, or the connection ends (IOException). */
    fun run(sink: FrameSink) {
        socket.connect(InetSocketAddress(host, PORT), 5000)
        socket.soTimeout = 5000  // metrics come every second: silence this long is a lost link
        val output = socket.getOutputStream()
        val request = ("U,${datagrams.localPort}\n" + DashKitLine.STREAM_ALL).toByteArray()
        thread(isDaemon = true) {
            try { while (!socket.isClosed) { output.write(request); Thread.sleep(1000) } } catch (e: Exception) { }
        }
        val clock = Clock()
        val busClock = DeviceClock()
        val payload = ByteArray(64)
        thread(isDaemon = true) { receiveDatagrams(sink, clock) }
        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        // read by hand, not by readLine: a link that never sends a newline can't grow a line without end
        val line = StringBuilder(64)
        while (true) {
            val c = input.read()
            if (c < 0) break
            if (c == '\n'.code) {
                val text = line.toString()
                line.setLength(0)
                if (text.startsWith("A,") || text.startsWith("E,")) { if (!DashKitLine.parse(text, clock.micros(), payload, sink, busClock)) throw IOException("stopped") } else status.read(text)
            } else if (c != '\r'.code && line.length < MAX_LINE) line.append(c.toChar())
        }
    }

    /** The packed frame datagrams ([DashKitLine.parsePacked]), from the DashKit's address only; a skipped sequence is a lost datagram. */
    private fun receiveDatagrams(sink: FrameSink, clock: Clock) {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        val busClock = DeviceClock()
        var expected = -1L
        try {
            while (!datagrams.isClosed) {
                packet.length = buffer.size
                datagrams.receive(packet)
                if (packet.address != socket.inetAddress) continue
                val sequence = DashKitLine.parsePacked(buffer, packet.length, clock.micros(), sink, busClock)
                if (sequence == -2L) { close(); return }
                if (sequence < 0) continue
                if (expected >= 0 && sequence > expected) status.datagramsLost += sequence - expected
                expected = sequence + 1
                status.datagrams++
            }
        } catch (e: IOException) {
            close()   // the TCP side sees it and the stream reconnects
        }
    }

    override fun close() { socket.close(); datagrams.close() }

    companion object {
        const val PORT = 62402
        private const val MAX_LINE = 1024
    }
}
