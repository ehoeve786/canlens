package com.ehoeve.canpilot

import java.util.concurrent.ConcurrentLinkedQueue
import com.ehoeve.canpilot.core.DashKitStatus
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
import com.ehoeve.canpilot.core.DashKitLine
import com.ehoeve.canpilot.core.FrameSink
import com.ehoeve.canpilot.core.appendCandump
import com.ehoeve.canpilot.core.appendMarker
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

fun Context.displayName(uri: Uri): String =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: uri.lastPathSegment ?: "file"

/** The file's content, decompressed if it is zstd (rlog.zst), bzip2 (rlog.bz2) or gzip. */
fun Context.openDecompressed(uri: Uri): InputStream {
    val raw = BufferedInputStream(contentResolver.openInputStream(uri) ?: throw IOException("Can't open ${displayName(uri)}"), 1 shl 16)
    raw.mark(4)
    val magic = ByteArray(4)
    val n = raw.read(magic)
    raw.reset()
    fun at(i: Int) = if (i < n) magic[i].toInt() and 0xFF else -1
    return when {
        at(0) == 0x28 && at(1) == 0xB5 && at(2) == 0x2F && at(3) == 0xFD -> ZstdInputStream(raw).setContinuous(true)
        at(0) == 'B'.code && at(1) == 'Z'.code && at(2) == 'h'.code -> BZip2CompressorInputStream(raw, true)
        at(0) == 0x1F && at(1) == 0x8B -> GZIPInputStream(raw)
        else -> raw
    }
}

/** Monotonic microseconds that start at the wall clock: a clock change mid-stream can't reorder frames. */
class Clock {
    private val wallMicros = System.currentTimeMillis() * 1000
    private val startNanos = SystemClock.elapsedRealtimeNanos()
    fun micros() = wallMicros + (SystemClock.elapsedRealtimeNanos() - startNanos) / 1000
}

/** Writes a live stream to `Download/CanPilot/can-log-<time>.log` in candump format, stamped with the phone's clock. */
class Recorder(context: Context) : Closeable {
    val name = "can-log-" + SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date()) + ".log"
    private val clock = Clock()
    private val line = StringBuilder(160)
    val uri: Uri
    private val writer: Writer

    init {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CanPilot")
        }
        uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Can't create $name in Downloads")
        writer = (context.contentResolver.openOutputStream(uri) ?: throw IOException("Can't write $name")).bufferedWriter(Charsets.US_ASCII)
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
class DashKitUsb(private val context: Context) : Closeable {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var closed = false

    /** What the DashKit last reported about transmitting and its CAN controllers. */
    val status = DashKitStatus()
    /** While true the firmware is kept armed to transmit; it disarms itself two seconds after this stops. */
    @Volatile var armed = false
    private val outbox = ConcurrentLinkedQueue<ByteArray>()

    /** Queues a line for the DashKit ([DashKitLine.transmit]); it goes out within a few milliseconds. */
    fun send(line: String) { outbox += line.toByteArray(Charsets.US_ASCII) }

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

    /** Reads frames into [sink] until [close], the sink returns false, or the cable is pulled (IOException). */
    fun run(sink: FrameSink) {
        val device = find() ?: throw IOException("No DashKit on USB")
        if (!permitted(device)) throw IOException("USB access to the DashKit was not allowed")
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
            val clock = Clock()
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
                        if (line.startsWith("A,")) { if (!DashKitLine.parse(line.toString(), time, payload, sink)) return } else status.read(line.toString())
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
        private const val ACTION_PERMISSION = "com.ehoeve.canpilot.USB_PERMISSION"
    }
}
