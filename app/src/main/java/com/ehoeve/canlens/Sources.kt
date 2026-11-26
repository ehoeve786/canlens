package com.ehoeve.canlens

import java.util.concurrent.ConcurrentLinkedQueue
import com.ehoeve.canlens.core.DashKitStatus
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
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
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(ACTION_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, IntentFilter(ACTION_PERMISSION))
        try {
            manager.requestPermission(device, intent)
            answered.await(60, TimeUnit.SECONDS)
        } finally {
            context.unregisterReceiver(receiver)
        }
        return manager.hasPermission(device)
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
                        if (line.startsWith("A,")) { if (!DashKitLine.parse(line.toString(), time, payload, sink, busClock)) return } else status.read(line.toString())
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
    val status = DashKitStatus()

    /** Reads frames into [sink] until [close], the sink returns false, or the connection ends (IOException). */
    fun run(sink: FrameSink) {
        socket.connect(InetSocketAddress(host, PORT), 5000)
        socket.soTimeout = 5000  // metrics come every second: silence this long is a lost link
        val output = socket.getOutputStream()
        val request = DashKitLine.STREAM_ALL.toByteArray()
        thread(isDaemon = true) {
            try { while (!socket.isClosed) { output.write(request); Thread.sleep(1000) } } catch (e: Exception) { }
        }
        val clock = Clock()
        val busClock = DeviceClock()
        val payload = ByteArray(64)
        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        // read by hand, not by readLine: a link that never sends a newline can't grow a line without end
        val line = StringBuilder(64)
        while (true) {
            val c = input.read()
            if (c < 0) break
            if (c == '\n'.code) {
                val text = line.toString()
                line.setLength(0)
                if (text.startsWith("A,")) { if (!DashKitLine.parse(text, clock.micros(), payload, sink, busClock)) throw IOException("stopped") } else status.read(text)
            } else if (c != '\r'.code && line.length < MAX_LINE) line.append(c.toChar())
        }
    }

    override fun close() = socket.close()

    companion object {
        const val PORT = 62402
        private const val MAX_LINE = 1024
    }
}
