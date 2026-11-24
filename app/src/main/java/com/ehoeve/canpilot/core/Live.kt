package com.ehoeve.canpilot.core

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

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

    fun parse(line: String, timeMicros: Long, buffer: ByteArray, sink: FrameSink): Boolean {
        if (!line.startsWith("A,")) return true
        val c2 = line.indexOf(',', 2)
        val c3 = line.indexOf(',', c2 + 1)
        if (c2 < 0 || c3 < 0) return true
        val bus = line.substring(2, c2).toIntOrNull() ?: return true
        val id = line.substring(c2 + 1, c3).toLongOrNull(16) ?: return true
        val length = LogReader.hex(line, c3 + 1, line.length, buffer)
        return length < 0 || sink.frame(timeMicros, bus, id.toInt(), buffer, 0, length)
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
