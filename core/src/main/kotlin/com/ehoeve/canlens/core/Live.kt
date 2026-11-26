package com.ehoeve.canlens.core

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Reads candump lines from a TCP port, as dashkitd on the comma serves the DashKit's chassis bus ([PORT]): the bus the
 * comma's harness doesn't reach, stamped with the comma's monotonic clock like its own `can` events, so the two line up.
 */
class CandumpStream(private val host: String, private val port: Int = PORT) : Closeable {
    private val socket = Socket()

    /** Blocks until [close], the sink says stop, or the connection ends (IOException). */
    fun run(sink: FrameSink) {
        socket.connect(InetSocketAddress(host, port), 5000)
        val buffer = ByteArray(FrameStore.MAX_PAYLOAD)
        socket.getInputStream().bufferedReader(Charsets.US_ASCII).forEachLine { line ->
            if (!LogReader.candump(line, buffer, sink)) throw IOException("stopped")
        }
    }

    override fun close() = socket.close()

    companion object {
        const val PORT = 62401
    }
}

/** Finds a comma device by the ZeroMQ publisher it runs (cereal's bridge), on the networks this device is in. */
object CommaFinder {
    /** Wi-Fi, a hotspot's own interface, Ethernet: not the mobile network, whose neighbours are strangers. */
    private val LOCAL = Regex("^(wlan|swlan|ap|softap|wifi|eth)", RegexOption.IGNORE_CASE)
    private val SIGNATURE = ByteArray(10).also { it[0] = 0xFF.toByte(); it[9] = 0x7F }

    /** True if [host] answers on [port] with the signature that opens every ZeroMQ greeting. */
    fun publishes(host: InetAddress, port: Int, timeoutMs: Int = 400): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.getOutputStream().write(SIGNATURE)
            val answer = ByteArray(10)
            DataInputStream(socket.getInputStream()).readFully(answer)
            answer[0] == 0xFF.toByte() && answer[9] == 0x7F.toByte()
        }
    } catch (e: IOException) {
        false
    }

    /** Every other address of the /24 this device has on each local network (a phone's hotspot clients among them). */
    fun neighbours(): List<InetAddress> {
        val found = ArrayList<InetAddress>()
        for (network in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            if (!network.isUp || network.isLoopback || !LOCAL.containsMatchIn(network.name)) continue
            for (address in network.interfaceAddresses) {
                val own = (address.address as? Inet4Address)?.takeIf { it.isSiteLocalAddress }?.address ?: continue
                for (last in 1..254) if (last != (own[3].toInt() and 0xFF)) found += InetAddress.getByAddress(byteArrayOf(own[0], own[1], own[2], last.toByte()))
            }
        }
        return found
    }

    /** True if [host] takes a connection on [port]: the DashKit's Wi-Fi link has a port of its own, and says nothing until asked. */
    fun accepts(host: InetAddress, port: Int, timeoutMs: Int = 600): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: IOException) {
        false
    }

    /** The first of [candidates] that passes [test]; all are tried at once, so a /24 takes about a second. Null if none. */
    fun find(candidates: List<InetAddress>, port: Int, test: (InetAddress, Int) -> Boolean = ::publishes): InetAddress? {
        if (candidates.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(minOf(THREADS, candidates.size))
        return try {
            pool.invokeAny(candidates.map { host -> Callable { if (test(host, port)) host else throw IOException("no publisher") } })
        } catch (e: ExecutionException) {
            null
        } finally {
            pool.shutdownNow()
        }
    }

    private const val THREADS = 64
}

/**
 * Subscribes to one openpilot service that the device publishes over ZeroMQ (cereal's `bridge`, or a process
 * started with ZMQ=1) and hands every `can` Event's frames to a sink. A minimal ZMTP 3.0 SUB socket over plain TCP.
 */
class CommaStream(private val host: String, private val port: Int = cerealPort("can")) : Closeable {
    private val socket = Socket()

    /** Blocks until [close] or an error; throws IOException when the connection fails or ends. */
    fun run(sink: FrameSink) {
        socket.connect(InetSocketAddress(host, port), 5000)
        socket.tcpNoDelay = true
        socket.soTimeout = 3000
        val out = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream().buffered(1 shl 16))
        // greeting: signature, version 3.0, mechanism NULL, not a server
        val greeting = ByteArray(64)
        greeting[0] = 0xFF.toByte(); greeting[9] = 0x7F; greeting[10] = 3
        "NULL".toByteArray().copyInto(greeting, 12)
        out.write(greeting)
        out.flush()
        input.readFully(greeting)
        if (greeting[0] != 0xFF.toByte() || greeting[10] < 3) throw IOException("$host:$port is not a ZeroMQ 3 publisher")
        val ready = byteArrayOf(5) + "READY".toByteArray() + byteArrayOf(11) + "Socket-Type".toByteArray() + byteArrayOf(0, 0, 0, 3) + "SUB".toByteArray()
        out.write(byteArrayOf(4, ready.size.toByte()) + ready)
        out.flush()
        var message = ByteArray(1 shl 16)
        val starts = IntArray(513)
        while (true) {
            val flags = try { input.readUnsignedByte() } catch (e: SocketTimeoutException) { continue }   // nothing published yet
            socket.soTimeout = 10000   // inside a frame now: a stall this long means the link is gone
            val size = if (flags and 2 != 0) input.readLong() else input.readUnsignedByte().toLong()
            if (size !in 0..MAX_FRAME) throw IOException("Bad frame from $host:$port")
            if (message.size < size) message = ByteArray(size.toInt())
            input.readFully(message, 0, size.toInt())
            socket.soTimeout = 3000
            if (flags and 4 != 0) {
                // the publisher's READY ends the handshake: subscribe to every topic (a one-byte message, 1)
                out.write(byteArrayOf(0, 1, 1)); out.flush()
                continue
            }
            if (size < 8) continue
            // one Cap'n Proto message: segment count - 1, the segment sizes in words, padding to a word, the segments
            val segments = Cereal.int(message, 0) + 1
            if (segments !in 1..512) continue
            var at = (4 + 4 * segments + 7) / 8 * 8
            if (at > size) continue
            for (s in 0 until segments) { starts[s] = at; at += Cereal.int(message, 4 + 4 * s) * 8 }
            starts[segments] = at
            if (at < 0 || at > size) continue
            if (!Cereal.canFrames(message, starts, segments, sink)) return
        }
    }

    override fun close() = socket.close()

    companion object {
        private const val MAX_FRAME = 16L shl 20

        /** The TCP port msgq's ZeroMQ backend gives a service: an FNV-1a hash of its name. */
        fun cerealPort(service: String): Int {
            var hash = 0xcbf29ce484222325UL
            for (c in service) { hash = hash xor (c.code.toULong() and 0xFFUL); hash *= 0x100000001b3UL }
            return 8023 + (hash % (65535UL - 8023UL)).toInt()
        }
    }
}

/** The DashKit's USB serial lines (fork firmware): `A,<bus>,<id hex>,<data hex>` is one CAN frame; the rest is status. */
object DashKitLine {
    /** A line that asks the firmware for every frame of both buses for the next two seconds; sent once a second. */
    const val STREAM_ALL = "L,1\n"
    /** Arms transmitting for the next two seconds (sent once a second while armed), and ends it at once. */
    const val ARM = "X,1\n"
    const val DISARM = "X,0\n"

    /** A line that asks the firmware to put a frame on a bus: standard ids, at most 8 bytes, bus 0 or 1; null for anything else. */
    fun transmit(bus: Int, id: Int, data: ByteArray): String? {
        if (bus !in 0..1 || id !in 0..0x7FF || data.isEmpty() || data.size > 8) return null
        return buildString(32) {
            append("T,").append(bus).append(',').append(Integer.toHexString(id)).append(',')
            for (b in data) append(HEX[b.toInt() shr 4 and 15]).append(HEX[b.toInt() and 15])
            append('\n')
        }
    }

    private const val HEX = "0123456789abcdef"

    /** An "A" line into [sink], at the time it came off the bus when the line has one ([clock]), else at [timeMicros]. */
    fun parse(line: String, timeMicros: Long, buffer: ByteArray, sink: FrameSink, clock: DeviceClock? = null): Boolean {
        if (!line.startsWith("A,")) return true
        val c2 = line.indexOf(',', 2)
        val c3 = line.indexOf(',', c2 + 1)
        if (c2 < 0 || c3 < 0) return true
        val c4 = line.indexOf(',', c3 + 1)
        val bus = line.substring(2, c2).toIntOrNull() ?: return true
        val id = line.substring(c2 + 1, c3).toLongOrNull(16) ?: return true
        val length = LogReader.hex(line, c3 + 1, if (c4 < 0) line.length else c4, buffer)
        val device = if (c4 < 0 || clock == null) null else line.substring(c4 + 1).toLongOrNull(16)
        return length < 0 || sink.frame(if (device == null) timeMicros else clock!!.micros(device, timeMicros), bus, id.toInt(), buffer, 0, length)
    }
}

/**
 * The DashKit's frame times (µs since its boot, 32 bits) on the phone's clock: the first frame of a connection sets
 * the offset, so frames keep the spacing they had on the bus whatever the link does to them; wraps (every 71.6 min)
 * are counted. The two clocks drift apart by well under a second an hour.
 */
class DeviceClock {
    private var last = -1L
    private var wraps = 0L
    private var offset = 0L

    fun micros(device: Long, arrival: Long): Long {
        if (last < 0) offset = arrival - device
        else if (device < last && last - device > 0x80000000L) wraps += 0x100000000L
        last = device
        return device + wraps + offset
    }
}

/** Appends microseconds as decimal seconds with six decimals. */
fun StringBuilder.appendSeconds(timeMicros: Long): StringBuilder {
    append(timeMicros / 1_000_000).append('.')
    val fraction = (timeMicros % 1_000_000).toString()
    repeat(6 - fraction.length) { append('0') }
    return append(fraction)
}

/** A note in a recording: a comment line that candump readers skip and [LogReader] reads back. */
const val MARKER_PREFIX = "# marker ("

fun StringBuilder.appendMarker(timeMicros: Long, note: String) {
    append(MARKER_PREFIX).appendSeconds(timeMicros).append(") ").append(note.replace('\n', ' ').replace('\r', ' ')).append('\n')
}

/** Appends one frame in candump's log format, the one DashPilot records and [LogReader] reads back. */
fun StringBuilder.appendCandump(timeMicros: Long, bus: Int, id: Int, data: ByteArray, offset: Int, length: Int) {
    val hex = "0123456789ABCDEF"
    append('(').appendSeconds(timeMicros).append(") can").append(bus).append(' ')
    for (shift in (if (id in 0..0x7FF) 8 else 28) downTo 0 step 4) append(hex[id ushr shift and 15])
    append('#')
    for (i in offset until offset + length) { append(hex[data[i].toInt() ushr 4 and 15]); append(hex[data[i].toInt() and 15]) }
    append('\n')
}
