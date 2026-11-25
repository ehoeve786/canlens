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

/** [source] is the message's text in the file it was read from, written back as it was while the message is unchanged. */
class DbcMessage(val id: Int, val name: String, val length: Int, val signals: List<Signal>, val source: String? = null) {
    val multiplexor: Signal? = signals.firstOrNull { it.isMultiplexor }
}

/**
 * A DBC file. [head] is its text before the first message, [tail] the lines after the messages that are not signals:
 * comments, attributes, value names. Both are written back unchanged, so editing one message in the app keeps
 * everything else in the file as it was.
 */
class Dbc(val name: String, val messages: Map<Int, DbcMessage>, val head: String = "", val tail: List<String> = emptyList()) {

    /** A copy with [signal] added to message [id] (created as [messageName], [length] bytes, if new), replacing one of the same name. */
    fun withSignal(id: Int, messageName: String, length: Int, signal: Signal): Dbc {
        val old = messages[id]
        val signals = (old?.signals.orEmpty().filter { it.name != signal.name } + signal).sortedBy { it.bits.min() }
        return Dbc(name, messages + (id to DbcMessage(id, old?.name ?: messageName, maxOf(old?.length ?: 0, length), signals)), head, tail)
    }

    /** A copy with message [id] named [messageName]; a message it doesn't have yet is added, [length] bytes, without signals. */
    fun withMessageName(id: Int, messageName: String, length: Int): Dbc {
        val old = messages[id]
        return Dbc(name, messages + (id to DbcMessage(id, messageName, old?.length ?: length, old?.signals.orEmpty())), head, tail)
    }

    fun withoutSignal(id: Int, signalName: String): Dbc {
        val old = messages[id] ?: return this
        return Dbc(name, messages + (id to DbcMessage(id, old.name, old.length, old.signals.filter { it.name != signalName })), head, tail.filter { !isStatus(it, id, signalName) })
    }

    /**
     * How sure the decoding of each signal is (guess, plotted, confirmed), kept as a comment in the file so it goes
     * where the DBC goes: `CM_ SG_ <id> <signal> "CanPilot: confirmed";`. Keyed by id and signal name.
     */
    val statuses: Map<Pair<Int, String>, String> by lazy {
        tail.mapNotNull { STATUS.find(it) }.associate { (id(it.groupValues[1]) to it.groupValues[2]) to it.groupValues[3] }
    }

    /** A copy with the status of signal [signalName] of message [id] set, or cleared when [status] is null. */
    fun withStatus(id: Int, signalName: String, status: String?): Dbc =
        Dbc(name, messages, head, tail.filter { !isStatus(it, id, signalName) } + listOfNotNull(status?.let { "CM_ SG_ ${dbcId(id)} $signalName \"CanPilot: $it\";" }))

    private fun isStatus(line: String, id: Int, signalName: String) = STATUS.find(line)?.let { id(it.groupValues[1]) == id && it.groupValues[2] == signalName } == true

    /**
     * DBC text: the file it was read from with only the changed messages written anew (their value names too; their
     * comments and attributes stay). Ranges and receivers of a changed message's signals are not kept.
     */
    fun write(): String = buildString {
        append(head.ifEmpty { "VERSION \"\"\n\nNS_ :\n\nBS_:\n\nBU_: XXX\n\n" })
        for (m in messages.values) {
            if (m.source != null) { append(m.source); continue }
            append("BO_ ").append(dbcId(m.id)).append(' ').append(m.name).append(": ").append(m.length).append(" XXX\n")
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
        val changed = messages.values.filter { it.source == null }.mapTo(HashSet()) { it.id }
        for (line in tail) {
            val values = VALUES.find(line)
            if (values == null || id(values.groupValues[1]) !in changed) append(line).append('\n')
        }
        for (m in messages.values) if (m.id in changed) for (s in m.signals) if (s.valueNames.isNotEmpty()) {
            append("VAL_ ").append(dbcId(m.id)).append(' ').append(s.name)
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
        private val STATUS = Regex("""^CM_ SG_ (\d+) (\w+) "CanPilot: (\w+)";""")

        /** An id as a DBC writes it: bit 31 set for an extended id. */
        private fun dbcId(id: Int) = if (id > 0x7FF) id.toLong() or 0x80000000L else id.toLong()

        /** Bits 29-31 of a DBC id are flags (bit 31: extended id). */
        private fun id(text: String) = (text.toLong() and 0x1FFFFFFF).toInt()

        fun parse(text: String, name: String): Dbc {
            val messages = LinkedHashMap<Int, DbcMessage>()
            val head = StringBuilder()
            val tail = ArrayList<String>()
            val block = StringBuilder()
            var id = 0
            var messageName = ""
            var length = 0
            var signals: ArrayList<Signal>? = null
            var seenMessage = false
            fun finish() {
                signals?.let { messages[id] = DbcMessage(id, messageName, length, it, block.append('\n').toString()) }
                signals = null
                block.setLength(0)
            }
            for (line in text.replace("\r\n", "\n").lineSequence()) {
                if (line.startsWith("BO_ ")) {
                    finish()
                    seenMessage = true
                    val m = MESSAGE.find(line) ?: continue
                    id = id(m.groupValues[1]); messageName = m.groupValues[2]; length = m.groupValues[3].toInt()
                    signals = ArrayList()
                    block.append(line).append('\n')
                } else if (!seenMessage) {
                    head.append(line).append('\n')
                } else if (signals != null && line.trimStart().startsWith("SG_ ")) {
                    block.append(line).append('\n')
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
                } else {
                    finish()
                    if (line.isNotBlank()) tail += line
                    val m = if (line.startsWith("VAL_ ")) VALUES.find(line) else null
                    val signal = m?.let { messages[id(it.groupValues[1])]?.signals?.firstOrNull { s -> s.name == it.groupValues[2] } } ?: continue
                    signal.valueNames = VALUE.findAll(m.groupValues[3]).associate { it.groupValues[1].toLong() to it.groupValues[2] }
                }
            }
            finish()
            return Dbc(name, messages, head.toString(), tail)
        }
    }
}
