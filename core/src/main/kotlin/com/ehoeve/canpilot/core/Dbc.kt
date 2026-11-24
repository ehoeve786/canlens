package com.ehoeve.canpilot.core

class Signal(
    val name: String,
    /** DBC start bit: the least significant bit for little-endian, the most significant for big-endian. */
    val start: Int,
    val size: Int,
    val littleEndian: Boolean,
    val signed: Boolean,
    val factor: Double,
    val offset: Double,
    val unit: String,
    /** Value of the message's multiplexor this signal is present for, or -1 when always present. */
    val muxValue: Int,
    val isMultiplexor: Boolean,
) {
    var valueNames: Map<Long, String> = emptyMap()
        internal set

    private val lastByte = if (littleEndian) (start + size - 1) / 8 else start / 8 + (size - (start % 8 + 1) + 7).coerceAtLeast(0) / 8

    /** Whether a payload of [length] bytes holds the whole signal. */
    fun fits(length: Int) = lastByte < length

    /** Raw value from the payload starting at [offset] in [data]; the caller has checked [fits]. */
    fun raw(data: ByteArray, offset: Int = 0): Long {
        var value = 0L
        if (littleEndian) {
            var bit = start
            var got = 0
            while (got < size) {
                val shift = bit and 7
                val n = minOf(8 - shift, size - got)
                value = value or (((data[offset + (bit ushr 3)].toLong() and 0xFF) ushr shift and ((1L shl n) - 1)) shl got)
                got += n
                bit += n
            }
        } else {
            var byte = offset + (start ushr 3)
            var high = start and 7
            var left = size
            while (left > 0) {
                val n = minOf(high + 1, left)
                value = (value shl n) or ((data[byte].toLong() and 0xFF) ushr (high + 1 - n) and ((1L shl n) - 1))
                left -= n
                byte++
                high = 7
            }
        }
        if (signed && size < 64 && value ushr (size - 1) and 1L == 1L) value -= 1L shl size
        return value
    }

    /** A 64-bit unsigned signal's raw value is the Long's bits read as unsigned. */
    fun physical(raw: Long) = (if (raw < 0 && !signed) raw.toULong().toDouble() else raw.toDouble()) * factor + offset

    /** The payload bits (byte * 8 + bit, bit 0 the least significant) this signal occupies, least significant first. */
    val bits: IntArray by lazy {
        IntArray(size).also { bits ->
            if (littleEndian) for (i in 0 until size) bits[i] = start + i
            else {
                var bit = start
                for (i in size - 1 downTo 0) { bits[i] = bit; bit = if (bit and 7 == 0) bit + 15 else bit - 1 }
            }
        }
    }
}

class DbcMessage(val id: Int, val name: String, val length: Int, val signals: List<Signal>) {
    val multiplexor: Signal? = signals.firstOrNull { it.isMultiplexor }
}

class Dbc(val name: String, val messages: Map<Int, DbcMessage>) {

    /** A copy with [signal] added to message [id] (created as [messageName], [length] bytes, if new), replacing one of the same name. */
    fun withSignal(id: Int, messageName: String, length: Int, signal: Signal): Dbc {
        val old = messages[id]
        val signals = (old?.signals.orEmpty().filter { it.name != signal.name } + signal).sortedBy { it.bits.min() }
        return Dbc(name, messages + (id to DbcMessage(id, old?.name ?: messageName, maxOf(old?.length ?: 0, length), signals)))
    }

    fun withoutSignal(id: Int, signalName: String): Dbc {
        val old = messages[id] ?: return this
        return Dbc(name, messages + (id to DbcMessage(id, old.name, old.length, old.signals.filter { it.name != signalName })))
    }

    /** DBC text with the messages, signals and value names; comments and attributes of the file it was read from are not kept. */
    fun write(): String = buildString {
        append("VERSION \"\"\n\nNS_ :\n\nBS_:\n\nBU_: XXX\n\n")
        for (m in messages.values) {
            append("BO_ ").append(if (m.id > 0x7FF) m.id.toLong() or 0x80000000L else m.id.toLong()).append(' ').append(m.name).append(": ").append(m.length).append(" XXX\n")
            for (s in m.signals) {
                append(" SG_ ").append(s.name).append(' ')
                if (s.muxValue >= 0) append('m').append(s.muxValue)
                if (s.isMultiplexor) append('M')
                if (s.muxValue >= 0 || s.isMultiplexor) append(' ')
                append(": ").append(s.start).append('|').append(s.size).append('@').append(if (s.littleEndian) '1' else '0').append(if (s.signed) '-' else '+')
                append(" (").append(number(s.factor)).append(',').append(number(s.offset)).append(") [0|0] \"").append(s.unit).append("\" XXX\n")
            }
            append('\n')
        }
        for (m in messages.values) for (s in m.signals) if (s.valueNames.isNotEmpty()) {
            append("VAL_ ").append(if (m.id > 0x7FF) m.id.toLong() or 0x80000000L else m.id.toLong()).append(' ').append(s.name)
            for ((value, label) in s.valueNames) append(' ').append(value).append(" \"").append(label).append('"')
            append(" ;\n")
        }
    }

    private fun number(v: Double) = if (v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    companion object {
        private val MESSAGE = Regex("""^BO_ (\d+) (\w+) *: *(\d+)""")
        private val SIGNAL = Regex("""^\s*SG_ (\w+) *(M|m\d+M?)? *: *(\d+)\|(\d+)@([01])([+-]) *\(([^,]+),([^)]+)\) *\[[^]]*] *"([^"]*)"""")
        private val VALUES = Regex("""^VAL_ (\d+) (\w+) (.*)""")
        private val VALUE = Regex("""(-?\d+) "([^"]*)"""")

        /** Bits 29-31 of a DBC id are flags (bit 31: extended id). */
        private fun id(text: String) = (text.toLong() and 0x1FFFFFFF).toInt()

        fun parse(text: String, name: String): Dbc {
            val messages = LinkedHashMap<Int, DbcMessage>()
            var id = 0
            var messageName = ""
            var length = 0
            var signals: ArrayList<Signal>? = null
            fun finish() { signals?.let { messages[id] = DbcMessage(id, messageName, length, it) }; signals = null }
            for (line in text.lineSequence()) {
                if (line.startsWith("BO_ ")) {
                    finish()
                    val m = MESSAGE.find(line) ?: continue
                    id = id(m.groupValues[1]); messageName = m.groupValues[2]; length = m.groupValues[3].toInt()
                    signals = ArrayList()
                } else if (signals != null && line.trimStart().startsWith("SG_ ")) {
                    val m = SIGNAL.find(line) ?: continue
                    val g = m.groupValues
                    val mux = g[2]
                    signals!! += Signal(
                        name = g[1], start = g[3].toInt(), size = g[4].toInt().coerceIn(1, 64),
                        littleEndian = g[5] == "1", signed = g[6] == "-",
                        factor = g[7].trim().toDoubleOrNull() ?: 1.0, offset = g[8].trim().toDoubleOrNull() ?: 0.0, unit = g[9],
                        muxValue = if (mux.startsWith("m")) mux.substring(1).trimEnd('M').toInt() else -1,
                        isMultiplexor = mux.endsWith("M"),
                    )
                } else if (line.startsWith("VAL_ ")) {
                    finish()
                    val m = VALUES.find(line) ?: continue
                    val signal = messages[id(m.groupValues[1])]?.signals?.firstOrNull { it.name == m.groupValues[2] } ?: continue
                    signal.valueNames = VALUE.findAll(m.groupValues[3]).associate { it.groupValues[1].toLong() to it.groupValues[2] }
                }
            }
            finish()
            return Dbc(name, messages)
        }
    }
}
