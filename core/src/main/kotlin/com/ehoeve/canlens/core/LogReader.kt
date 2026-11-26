package com.ehoeve.canlens.core

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** Reads CAN logs: openpilot rlog (uncompressed here), candump, Vector ASC and CSV (SavvyCAN or Cabana export). */
object LogReader {

    /**
     * Reads one log into [sink] until its end or until the sink returns false. Returns the format's name.
     * [onMarker] gets the notes of a recording (time in microseconds, text).
     */
    fun read(input: InputStream, sink: FrameSink, onMarker: ((Long, String) -> Unit)? = null): String {
        val stream = BufferedInputStream(input, 1 shl 16)
        stream.mark(512)
        val head = ByteArray(512)
        var n = 0
        while (n < head.size) { val got = stream.read(head, n, head.size - n); if (got < 0) break; n += got }
        stream.reset()
        // a UTF-8 byte order mark, as spreadsheet programs write before a CSV
        val bom = if (n >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) 3 else 0
        var skipped = 0L
        while (skipped < bom) skipped += stream.skip(bom - skipped)
        return if (looksLikeText(head, bom, n)) readText(stream, sink, onMarker) else readRlog(stream, sink)
    }

    /**
     * Text has no NUL byte (an rlog opens with its segment count, a 32-bit word that is mostly zeros) and is mostly
     * printable ASCII, so a name or unit in UTF-8 is fine. A line break isn't required: a CSV header can be long.
     */
    private fun looksLikeText(head: ByteArray, from: Int, to: Int): Boolean {
        var printable = 0
        for (i in from until to) {
            val c = head[i].toInt() and 0xFF
            if (c == 0) return false
            if (c in 32..126 || c == 10 || c == 13 || c == 9) printable++
        }
        return printable * 10 >= (to - from) * 9
    }

    private fun readRlog(stream: InputStream, sink: FrameSink): String {
        val input = DataInputStream(stream)
        var message = ByteArray(1 shl 16)
        val word = ByteArray(4)
        var starts = IntArray(8)
        while (true) {
            if (input.read(word, 0, 1) < 1) break   // clean end of the log
            val segments: Int
            try {
                input.readFully(word, 1, 3)
                segments = Cereal.int(word, 0) + 1
                if (segments !in 1..512) throw IOException("Not an openpilot log")
                if (starts.size <= segments) starts = IntArray(segments + 1)
                var total = 0L
                for (s in 0 until segments) {
                    input.readFully(word)
                    starts[s] = (total * 8).toInt()
                    total += Cereal.int(word, 0).toLong() and 0xFFFFFFFFL
                    if (total > MAX_MESSAGE_WORDS) throw IOException("Not an openpilot log")
                }
                starts[segments] = (total * 8).toInt()
                if (segments % 2 == 0) input.readFully(word)   // the segment table is padded to a whole word
                val size = starts[segments]
                if (message.size < size) message = ByteArray(size)
                input.readFully(message, 0, size)
            } catch (e: EOFException) {
                break   // a log cut off mid-message (the device lost power): keep what was read
            }
            if (!Cereal.canFrames(message, starts, segments, sink)) break
        }
        return "rlog"
    }

    private fun readText(stream: InputStream, sink: FrameSink, onMarker: ((Long, String) -> Unit)?): String {
        val reader = LineReader(stream)
        val buffer = ByteArray(FrameStore.MAX_PAYLOAD)
        var format: String? = null
        var csv: CsvColumns? = null
        var ascHex = true
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            if (line[0] == '#') {
                val close = line.indexOf(')')
                if (onMarker != null && close > 0 && line.startsWith(MARKER_PREFIX)) {
                    val time = micros(line, MARKER_PREFIX.length, close)
                    if (time >= 0) onMarker(time, line.substring(close + 1).trim())
                }
                continue
            }
            if (format == null) {
                format = when {
                    line[0] == '(' -> "candump"
                    ',' in line -> "CSV"
                    else -> "ASC"
                }
                if (format == "CSV") { csv = CsvColumns(line); continue }
            }
            val more = when (format) {
                "candump" -> candump(line, buffer, sink)
                "CSV" -> csv!!.row(line, buffer, sink)
                else -> {
                    if (line.startsWith("base ")) { ascHex = line.startsWith("base hex"); true } else asc(line, ascHex, buffer, sink)
                }
            }
            if (!more) break
        }
        return format ?: "empty"
    }

    /** `(1795462824.051000) can1 103#22B388068E462132`; CAN FD as `id##<flags>data`. Unparseable lines are skipped. */
    internal fun candump(line: String, buffer: ByteArray, sink: FrameSink): Boolean {
        val close = line.indexOf(')')
        val hash = line.indexOf('#', close + 1)
        if (close < 2 || line[0] != '(' || hash < 0) return true
        val space = line.lastIndexOf(' ', hash)
        // DashPilot logs the DashKit firmware's own statistics as diag0, diag1...; they are not CAN frames
        if (space <= close + 1 || line.startsWith("diag", close + 2)) return true
        val time = micros(line, 1, close)
        var busEnd = space
        while (busEnd > close + 2 && line[busEnd - 1] == ' ') busEnd--
        var busStart = busEnd
        while (busStart > close + 2 && line[busStart - 1].isDigit()) busStart--
        val bus = if (busStart < busEnd) line.substring(busStart, busEnd).toIntOrNull() ?: 0 else 0
        val id = line.substring(space + 1, hash).toLongOrNull(16)?.takeIf { it <= 0x1FFFFFFF } ?: return true
        var p = hash + 1
        if (p < line.length && line[p] == '#') p += 2
        val length = hex(line, p, line.length, buffer)
        if (time < 0 || length < 0) return true
        return sink.frame(time, bus, CanId.of(id, hash - space - 1 > 3), buffer, 0, length)
    }

    /** `   0.016728 1  123  Rx   d 8 3E 42 03 00 39 00 03 01`, channels counted from 1, an `x` after extended ids. */
    private fun asc(line: String, hexBase: Boolean, buffer: ByteArray, sink: FrameSink): Boolean {
        val t = line.trim().split(WHITESPACE)
        if (t.size < 6 || t[4] != "d" || !t[0][0].isDigit()) return true
        val radix = if (hexBase) 16 else 10
        val time = micros(t[0], 0, t[0].length)
        val channel = t[1].toIntOrNull() ?: return true
        val id = t[2].trimEnd('x', 'X').toLongOrNull(radix) ?: return true
        val length = (t[5].toIntOrNull(radix) ?: return true).coerceAtMost(minOf(t.size - 6, buffer.size))
        if (time < 0 || length < 0) return true
        for (i in 0 until length) buffer[i] = (t[6 + i].toIntOrNull(radix) ?: return true).toByte()
        return sink.frame(time, channel - 1, CanId.of(id, t[2].endsWith('x', ignoreCase = true)), buffer, 0, length)
    }

    /**
     * SavvyCAN (`Time Stamp,ID,Extended,Dir,Bus,LEN,D1..D8`, µs) and Cabana (`time,addr,bus,data`, seconds) exports, and
     * DashPilot's older recordings (`ms,bus,id,data`; a bus of 16 or 17 marks the DashKit firmware's own statistics).
     */
    private class CsvColumns(header: String) {
        private val names = header.lowercase().split(',').map { it.trim().trim('"') }
        private val milliseconds = names.indexOf("ms")
        private val time = if (milliseconds >= 0) milliseconds else names.indexOfFirst { it == "time stamp" || it == "time" || it == "timestamp" }
        private val id = names.indexOfFirst { it == "id" || it == "addr" || it == "address" }
        private val bus = names.indexOf("bus")
        private val data = names.indexOf("data")
        private val length = names.indexOfFirst { it == "len" || it == "dlc" }
        private val firstByte = names.indexOf("d1")
        private val extended = names.indexOf("extended")

        init { if (time < 0 || id < 0 || (data < 0 && firstByte < 0)) throw IOException("Unknown CSV columns: $header") }

        fun row(line: String, buffer: ByteArray, sink: FrameSink): Boolean {
            // quoted cells ("123") are read without their quotes; a comma inside quotes doesn't occur in these exports
            val c = line.split(',').map { it.trim().trim('"') }
            if (c.size <= maxOf(time, id, data, firstByte)) return true
            val stamp = c[time].trim()
            // a decimal point means seconds; SavvyCAN writes whole microseconds
            val micros = when {
                milliseconds >= 0 -> (stamp.toLongOrNull() ?: return true) * 1000
                '.' in stamp -> micros(stamp, 0, stamp.length)
                else -> stamp.toLongOrNull() ?: return true
            }
            val frameBus = c.getOrNull(bus)?.trim()?.toIntOrNull() ?: 0
            if (milliseconds >= 0 && frameBus and 0x10 != 0) return true
            val frameId = c[id].removePrefix("0x").removePrefix("0X").toLongOrNull(16)?.takeIf { it <= 0x1FFFFFFF } ?: return true
            val n = if (data >= 0) {
                val text = c[data].trim().removePrefix("0x").removePrefix("0X")
                hex(text, 0, text.length, buffer)
            } else {
                val count = minOf(c.getOrNull(length)?.trim()?.toIntOrNull() ?: 8, c.size - firstByte, buffer.size)
                var ok = count >= 0
                for (i in 0 until count) buffer[i] = (c[firstByte + i].trim().toIntOrNull(16) ?: run { ok = false; 0 }).toByte()
                if (ok) count else -1
            }
            if (micros < 0 || n < 0) return true
            val isExtended = c.getOrNull(extended)?.lowercase().let { it == "true" || it == "1" }
            return sink.frame(micros, frameBus, CanId.of(frameId, isExtended), buffer, 0, n)
        }
    }

    /** Hex digits of text[from until to] into [buffer]; the number of bytes, or -1 if it isn't clean hex that fits. */
    internal fun hex(text: String, from: Int, to: Int, buffer: ByteArray): Int {
        var end = to
        while (end > from && text[end - 1].isWhitespace()) end--
        val n = (end - from) / 2
        if ((end - from) % 2 != 0 || n > buffer.size) return -1
        for (i in 0 until n) {
            val high = Character.digit(text[from + 2 * i], 16)
            val low = Character.digit(text[from + 2 * i + 1], 16)
            if (high < 0 || low < 0) return -1
            buffer[i] = (high shl 4 or low).toByte()
        }
        return n
    }

    /** Decimal seconds in text[from until to] as microseconds, without going through a double; -1 if not a number. */
    internal fun micros(text: String, from: Int, to: Int): Long {
        var seconds = 0L
        var fraction = 0L
        var digits = -1
        if (from >= to) return -1
        for (i in from until to) {
            val c = text[i]
            if (c == '.' && digits < 0) { digits = 0; continue }
            if (c !in '0'..'9') return -1
            if (digits < 0) seconds = seconds * 10 + (c - '0') else if (digits < 6) { fraction = fraction * 10 + (c - '0'); digits++ }
        }
        while (digits in 0..5) { fraction *= 10; digits++ }
        return seconds * 1_000_000 + fraction
    }

    private val WHITESPACE = Regex("\\s+")
    private const val MAX_MESSAGE_WORDS = 8L shl 20   // 64 MB
}

/**
 * Lines of ISO 8859-1 text, ended by \n or \r (a \r\n gives an empty line between), without holding more than [max]
 * characters: a longer line, which no log has, is skipped whole.
 */
internal class LineReader(private val input: InputStream, private val max: Int = 4096) {
    private val buffer = ByteArray(1 shl 16)
    private var at = 0
    private var end = 0
    private val line = CharArray(max)

    /** The next line, or null at the end of the input. */
    fun readLine(): String? {
        var n = 0
        var tooLong = false
        while (true) {
            if (at == end) {
                end = input.read(buffer)
                at = 0
                if (end <= 0) { end = 0; return if (n > 0 && !tooLong) String(line, 0, n) else null }
            }
            val c = buffer[at++].toInt() and 0xFF
            if (c == 10 || c == 13) {
                if (!tooLong) return String(line, 0, n)
                n = 0; tooLong = false
            } else if (n < max) line[n++] = c.toChar() else tooLong = true
        }
    }
}

/** Just enough Cap'n Proto to take the CAN frames out of an openpilot `Event` (cereal/log.capnp). */
internal object Cereal {
    private const val EVENT_WHICH_OFFSET = 8
    private const val EVENT_CAN = 4
    private const val CAN_SRC_OFFSET = 6

    fun int(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or (b[o + 1].toInt() and 0xFF shl 8) or (b[o + 2].toInt() and 0xFF shl 16) or (b[o + 3].toInt() shl 24)

    private fun long(b: ByteArray, o: Int) = (int(b, o).toLong() and 0xFFFFFFFFL) or (int(b, o + 4).toLong() shl 32)

    /**
     * Passes the frames of the Event message in [m] (its segments start at [starts], which has one more entry for the
     * end) to [sink]. Events of other kinds and malformed ones are skipped. False once the sink wants no more.
     */
    fun canFrames(m: ByteArray, starts: IntArray, segments: Int, sink: FrameSink): Boolean {
        // [m] is reused and may be longer than this message: every read is kept below its end, not the array's
        val end = starts[segments]
        fun word(at: Int): Long { if (at < 0 || at + 8 > end) throw IndexOutOfBoundsException(); return long(m, at) }
        try {
            val event = follow(m, starts, segments, starts[0], end)
            if (event < 0 || word(event) and 3L != 0L) return true
            val root = word(event)
            val dataWords = (root ushr 32 and 0xFFFF).toInt()
            val struct = event + 8 + (root.toInt() shr 2) * 8
            if (dataWords < 2 || root ushr 48 and 0xFFFF == 0L) return true
            if ((word(struct + EVENT_WHICH_OFFSET) and 0xFFFF).toInt() != EVENT_CAN) return true
            val time = word(struct) / 1000
            val list = follow(m, starts, segments, struct + dataWords * 8, end)
            if (list < 0) return true
            val pointer = word(list)
            if (pointer and 3L != 1L || (pointer ushr 32 and 7).toInt() != 7) return true   // a list of structs
            val tag = list + 8 + (pointer.toInt() shr 2) * 8
            val count = word(tag).toInt() ushr 2
            val elementData = (word(tag) ushr 32 and 0xFFFF).toInt() * 8
            val elementSize = elementData + (word(tag) ushr 48 and 0xFFFF).toInt() * 8
            if (elementData < 8 || elementSize == elementData) return true
            for (i in 0 until count) {
                val e = tag + 8 + i * elementSize
                val bytes = follow(m, starts, segments, e + elementData, end)
                if (bytes < 0) continue
                val data = word(bytes)
                if (data and 3L != 1L || (data ushr 32 and 7).toInt() != 2) continue   // a list of bytes
                val length = (data ushr 35).toInt()
                val start = bytes + 8 + (data.toInt() shr 2) * 8
                if (length > FrameStore.MAX_PAYLOAD || start < 0 || start + length > end) continue
                if (!sink.frame(time, (word(e) ushr (CAN_SRC_OFFSET * 8) and 0xFF).toInt(), word(e).toInt(), m, start, length)) return false
            }
        } catch (e: IndexOutOfBoundsException) {
            // a pointer outside the message: skip it
        }
        return true
    }

    /** Offset of the pointer that the pointer at [at] stands for (itself unless it is a far pointer); -1 if null or unsupported. */
    private fun follow(m: ByteArray, starts: IntArray, segments: Int, at: Int, end: Int): Int {
        if (at < 0 || at + 8 > end) throw IndexOutOfBoundsException()
        val p = long(m, at)
        if (p == 0L) return -1
        if (p and 3L != 2L) return at
        val segment = (p ushr 32).toInt()
        if (p and 4L != 0L || segment !in 0 until segments) return -1   // double-far pointers don't occur in these logs
        return starts[segment] + (p.toInt() ushr 3) * 8
    }
}
