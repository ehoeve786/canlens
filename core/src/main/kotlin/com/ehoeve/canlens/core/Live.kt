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
class CandumpStream(private val host: String, private val port: Int = PORT, private val silenceMs: Int = SILENCE_MS) : Closeable {
    private val socket = Socket()

    /** Blocks until [close], the sink says stop, or the connection ends or says nothing for [silenceMs] (IOException). */
    fun run(sink: FrameSink) {
        socket.connect(InetSocketAddress(host, port), 5000)
        socket.soTimeout = silenceMs
        val buffer = ByteArray(FrameStore.MAX_PAYLOAD)
        val lines = LineReader(socket.getInputStream())
        while (true) {
            val line = try { lines.readLine() } catch (e: SocketTimeoutException) { throw IOException("$host:$port sent nothing for ${silenceMs / 1000.0} s") } ?: return
            if (!LogReader.candump(line, buffer, sink)) throw IOException("stopped")
        }
    }

    override fun close() = socket.close()

    companion object {
        const val PORT = 62401
    }
}

/** How long a live link may stay silent before it counts as gone and is dropped, for the caller to connect again. */
const val SILENCE_MS = 10_000

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
class CommaStream(private val host: String, private val port: Int = cerealPort("can"), private val silenceMs: Int = SILENCE_MS) : Closeable {
    private val socket = Socket()

    /** Blocks until [close] or an error; throws IOException when the connection fails, ends or says nothing for [silenceMs]. */
    fun run(sink: FrameSink) {
        socket.connect(InetSocketAddress(host, port), 5000)
        socket.tcpNoDelay = true
        socket.soTimeout = silenceMs
        try {
            stream(sink)
        } catch (e: SocketTimeoutException) {
            // the `can` service publishes many times a second: a link this quiet is taken as gone
            throw IOException("$host:$port sent nothing for ${silenceMs / 1000.0} s")
        }
    }

    private fun stream(sink: FrameSink) {
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
            val flags = input.readUnsignedByte()
            val size = if (flags and 2 != 0) input.readLong() else input.readUnsignedByte().toLong()
            if (size !in 0..MAX_FRAME) throw IOException("Bad frame from $host:$port")
            if (message.size < size) message = ByteArray(size.toInt())
            input.readFully(message, 0, size.toInt())
            if (flags and 4 != 0) {
                // a command: its name, then its properties
                val name = if (size >= 1 && (message[0].toInt() and 0xFF) < size) String(message, 1, message[0].toInt() and 0xFF, Charsets.US_ASCII) else ""
                if (name == "ERROR") {
                    val reason = if (size >= 7 && 7 + (message[6].toInt() and 0xFF) <= size) String(message, 7, message[6].toInt() and 0xFF, Charsets.US_ASCII) else "no reason given"
                    throw IOException("$host:$port refused the connection: $reason")
                }
                // the publisher's READY ends the handshake: subscribe to every topic (a one-byte message, 1)
                if (name == "READY") { out.write(byteArrayOf(0, 1, 1)); out.flush() }
                continue
            }
            if (size < 8) continue
            // one Cap'n Proto message: segment count - 1, the segment sizes in words, padding to a word, the segments
            val segments = Cereal.int(message, 0) + 1
            if (segments !in 1..512) continue
            // sizes are unsigned words: summed as a Long, so a garbage size can't wrap around into range
            var at = (4L + 4 * segments + 7) / 8 * 8
            if (at > size) continue
            for (s in 0 until segments) { starts[s] = at.toInt(); at += (Cereal.int(message, 4 + 4 * s).toLong() and 0xFFFFFFFFL) * 8; if (at > size) break }
            if (at > size) continue
            starts[segments] = at.toInt()
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

    /**
     * A datagram of packed frames from the DashKit's Wi-Fi link (wifi_link.h, can_manager.c) into [sink]: a tag, its
     * sequence number, the first frame's bus time, then the frames, all little-endian. 'C' (compact): per frame a u16
     * (bit 15 bus, bits 11-14 length, 15 for extended or 14 for one the DashKit sent, bits 0-10 the id), the µs since
     * the frame before (u8, or 255 and a u16), for an extended one its length (u8), for a sent one its length with
     * bit 6 for extended (u8), for either extended kind the id (u32), then the data. Sent frames go on bus 128 + bus. 'B' (firmware before it): a byte
     * (bit 7 bus, bit 6 extended, bits 0-3 length), the µs since the first (u16), the id (u16 or u32), the data.
     * Returns the sequence number, -1 when it isn't such a datagram (or is cut short), -2 when the sink said stop.
     */
    fun parsePacked(bytes: ByteArray, length: Int, arrival: Long, sink: FrameSink, clock: DeviceClock): Long {
        fun u8(at: Int) = bytes[at].toInt() and 0xFF
        fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
        fun u32(at: Int) = (u16(at).toLong() or (u16(at + 2).toLong() shl 16))
        val compact = length >= 9 && bytes[0] == 'C'.code.toByte()
        if (length < 9 || !compact && bytes[0] != 'B'.code.toByte()) return -1
        val sequence = u32(1)
        val start = u32(5)
        var time = start
        var at = 9
        while (at < length) {
            if (compact) {
                if (at + 3 > length) return -1
                val head = u16(at)
                var size = head shr 11 and 0x0F
                val sent = size == 14
                var extended = size == 15
                var next = at + 3
                var gap = u8(at + 2)
                if (gap == 255) { if (next + 2 > length) return -1; gap = u16(next); next += 2 }
                var id = (head and 0x7FF).toLong()
                if (sent || extended) {
                    if (next + 1 > length) return -1
                    size = u8(next) and 0x0F
                    extended = extended || u8(next) and 0x40 != 0
                    next++
                }
                if (extended) { if (next + 4 > length) return -1; id = u32(next); next += 4 }
                if (size > 8 || next + size > length) return -1
                time = (time + gap) and 0xFFFFFFFFL
                if (!sink.frame(clock.micros(time, arrival), (head shr 15) + if (sent) 128 else 0, CanId.of(id, extended), bytes, next, size)) return -2
                at = next + size
            } else {
                val head = u8(at)
                val extended = head and 0x40 != 0
                val size = head and 0x0F
                val data = at + 3 + (if (extended) 4 else 2)
                if (size > 8 || data + size > length) return -1
                val id = if (extended) u32(at + 3) else u16(at + 3).toLong()
                val device = (start + u16(at + 1)) and 0xFFFFFFFFL
                if (!sink.frame(clock.micros(device, arrival), head shr 7, CanId.of(id, extended), bytes, data, size)) return -2
                at = data + size
            }
        }
        return sequence
    }

    /**
     * An "A" line into [sink], or an "E" line (a frame the DashKit sent) on bus 128 + its bus, at the time it came off
     * the bus when the line has one ([clock]), else at [timeMicros].
     */
    fun parse(line: String, timeMicros: Long, buffer: ByteArray, sink: FrameSink, clock: DeviceClock? = null): Boolean {
        val sent = line.startsWith("E,")
        if (!sent && !line.startsWith("A,")) return true
        val c2 = line.indexOf(',', 2)
        val c3 = line.indexOf(',', c2 + 1)
        if (c2 < 0 || c3 < 0) return true
        val c4 = line.indexOf(',', c3 + 1)
        val bus = (line.substring(2, c2).toIntOrNull() ?: return true) + if (sent) 128 else 0
        val id = line.substring(c2 + 1, c3).toLongOrNull(16)?.takeIf { it <= 0x1FFFFFFF } ?: return true
        val length = LogReader.hex(line, c3 + 1, if (c4 < 0) line.length else c4, buffer)
        val device = if (c4 < 0 || clock == null) null else line.substring(c4 + 1).toLongOrNull(16)
        return length < 0 || sink.frame(if (device == null) timeMicros else clock!!.micros(device, timeMicros), bus, CanId.of(id, c3 - c2 - 1 > 3), buffer, 0, length)
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
        // a frame from just before the last wrap, arriving after one from just after it: the earlier lap
        else if (device > last && device - last > 0x80000000L) return device + wraps - 0x100000000L + offset
        last = device
        return device + wraps + offset
    }
}

/** Appends microseconds as decimal seconds with six decimals. */
fun StringBuilder.appendSeconds(timeMicros: Long): StringBuilder {
    // the sign, then both parts without it: -1.5 s is -1.500000, not -1.-500000
    if (timeMicros < 0) append('-')
    append(Math.abs(timeMicros / 1_000_000)).append('.')
    val fraction = Math.abs(timeMicros % 1_000_000).toString()
    repeat(6 - fraction.length) { append('0') }
    return append(fraction)
}

/** A note in a recording: a comment line that candump readers skip and [LogReader] reads back. */
const val MARKER_PREFIX = "# marker ("

fun StringBuilder.appendMarker(timeMicros: Long, note: String) {
    append(MARKER_PREFIX).appendSeconds(timeMicros).append(") ").append(note.replace('\n', ' ').replace('\r', ' ')).append('\n')
}

/** Appends one frame in candump's log format, the one DashPilot records and [LogReader] reads back; over 8 bytes, CAN FD's `id##<flags><data>`. */
fun StringBuilder.appendCandump(timeMicros: Long, bus: Int, id: Int, data: ByteArray, offset: Int, length: Int) {
    val hex = "0123456789ABCDEF"
    append('(').appendSeconds(timeMicros).append(") can").append(bus).append(' ')
    val raw = CanId.raw(id)
    for (shift in (if (CanId.isExtended(id)) 28 else 8) downTo 0 step 4) append(hex[raw ushr shift and 15])
    append(if (length > 8) "##0" else "#")
    for (i in offset until offset + length) { append(hex[data[i].toInt() ushr 4 and 15]); append(hex[data[i].toInt() and 15]) }
    append('\n')
}
