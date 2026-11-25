package com.ehoeve.canpilot.core

import kotlin.math.abs
import kotlin.math.sqrt

/** A signal the automatic decoder proposes, why, and how sure it is (0..1). Meant to be reviewed, not trusted. */
/** [messageName]: what to call the message when the DBC doesn't have it yet (NEW_<id> when null). */
class Suggestion(val message: FrameStore.Message, val signal: Signal, val evidence: String, val confidence: Double, val messageName: String? = null)

/**
 * Decodes what can be decoded without a person: the layout of every message (fields, counters, verified checksums,
 * multiplexing), then names for the fields that follow an already decoded signal or changed only at a marker.
 */
object AutoDecode {
    /**
     * What [community] DBCs (name, file) know about the messages in [store] that the user's DBCs ([known]) don't:
     * whole messages, or signals in bits the user's entry leaves free. A community message only counts when it fits
     * the frames seen (same length), which keeps another bus's message with the same id out.
     */
    fun fromCommunity(store: FrameStore, known: (FrameStore.Message) -> DbcMessage?, community: List<Pair<String, Dbc>>): List<Suggestion> {
        val result = ArrayList<Suggestion>()
        val samples = HashMap<Int, FrameStore.Payloads>()
        fun payloads(message: FrameStore.Message) = samples.getOrPut(message.index) { store.payloads(message) }
        for (message in store.messages) {
            if (message.bus >= 128 || message.data.isEmpty() || community.none { message.id in it.second.messages }) continue
            // the same frames on a lower bus too (the party bus is seen on 0 and 2): suggested there only
            if (store.messages.any { it.id == message.id && it.bus < message.bus && copies(payloads(message), payloads(it)) }) continue
            val mine = known(message)
            val taken = HashSet<Int>().apply { mine?.signals?.forEach { addAll(it.bits.asList()) } }
            // the first DBC that knows the message speaks for it: two files naming the same bits differently would only double the list
            var answered = false
            for ((source, dbc) in community) {
                if (answered) break
                val theirs = dbc.messages[message.id]?.takeIf { it.length == message.data.size } ?: continue
                // Model3CAN names its messages ID<hex><name>
                val name = theirs.name.removePrefix("ID%X".format(message.id)).ifEmpty { theirs.name }
                for (signal in theirs.signals) {
                    if (!signal.fits(message.data.size) || signal.bits.any { it in taken } || result.any { it.message === message && it.signal.name == signal.name }) continue
                    result += Suggestion(message, signal, "From $source ($name)", 0.75, name)
                    answered = true
                }
            }
        }
        return result
    }

    private const val GRID = 400
    private const val MATCH = 0.95
    private val PIPE = Regex("isotp|pipe", RegexOption.IGNORE_CASE)
    /** How closely the moments of change must agree too, and how many changes a signal needs to be compared at all. */
    private const val MATCH_CHANGES = 0.8f
    private const val MIN_CHANGES = 5

    /**
     * [known] gives the DBC entry of a message, if any; its signals are left alone and used as references.
     * [markers] are store times with their notes. [progress] is told what is being worked on.
     */
    fun run(store: FrameStore, known: (FrameStore.Message) -> DbcMessage?, markers: List<Pair<Int, String>>, progress: (String) -> Unit = {}): List<Suggestion> {
        val messages = store.messages
        val result = ArrayList<Suggestion>()
        progress("Working out the layout of ${messages.size} messages")
        val samples = HashMap<Int, FrameStore.Payloads>()
        fun payloads(message: FrameStore.Message) = samples.getOrPut(message.index) { store.payloads(message) }
        for (message in messages) {
            val payloads = payloads(message)
            if (payloads.size < 4) continue
            // the same id on a lower bus sending the same frames: a copy (forwarded, or a comma's echo of what it sent)
            if (messages.any { it.id == message.id && it.bus < message.bus && copies(payloads, payloads(it)) }) continue
            if (diagnostic(message, payloads)) continue
            val free = BooleanArray(payloads.length * 8) { true }
            val dbcMessage = known(message)
            // a pipe carries conversations (ISO-TP over a private id): its bytes are content, not a layout
            if (dbcMessage != null && PIPE.containsMatchIn(dbcMessage.name)) continue
            dbcMessage?.signals?.forEach { signal -> for (bit in signal.bits) if (bit in free.indices) free[bit] = false }
            checksums(message, payloads, free, result)
            layout(message, payloads, free, dbcMessage?.multiplexor?.takeIf { it.fits(payloads.length) }, result)
        }
        // markers first: they cut their bits out of the guessed fields, and what is left is then compared
        if (markers.isNotEmpty()) progress("Looking at what changed at ${markers.size} markers")
        // repeats of an action, and its two halves ("door open", "door closed"), are one test: the bit must change at every one
        for ((action, times) in markers.groupBy({ it.second.replace(HALF, "").trim().ifEmpty { it.second }.lowercase() }, { it.first })) changedOnlyAt(store, known, times, action, result)
        progress("Comparing ${result.size} fields with the decoded signals")
        nameAfterKnownSignals(store, known, result)
        return result.sortedWith(compareByDescending<Suggestion> { it.confidence }.thenBy { it.message.bus }.thenBy { it.message.id }.thenBy { it.signal.start })
    }

    /**
     * Diagnostic traffic (ISO-TP: OBD and UDS requests and answers) on the ids set aside for it: its first byte is a
     * frame type and length, not a field, and its contents are a conversation, not a signal.
     */
    private fun diagnostic(message: FrameStore.Message, p: FrameStore.Payloads): Boolean {
        val id = message.id
        if (id !in 0x700..0x7FF && id ushr 16 != 0x18DA && id ushr 16 != 0x18DB) return false
        var typed = 0
        for (k in 0 until p.size) if (p.data[k * p.length].toInt() and 0xF0 <= 0x30) typed++
        return typed * 20 >= p.size * 19
    }

    /** True when nearly every one of the first frames of [mine] is also a frame of [other]. */
    private fun copies(mine: FrameStore.Payloads, other: FrameStore.Payloads): Boolean {
        if (mine.length != other.length) return false
        val seen = HashSet<java.nio.ByteBuffer>(other.size * 2)
        for (k in 0 until other.size) seen += java.nio.ByteBuffer.wrap(other.data, k * other.length, other.length).slice()
        val n = minOf(mine.size, 200)
        var found = 0
        for (k in 0 until n) if (java.nio.ByteBuffer.wrap(mine.data, k * mine.length, mine.length).slice() in seen) found++
        return found * 10 >= n * 9
    }

    private fun field(name: String, start: Int, size: Int, signed: Boolean = false, mux: Int = -1, multiplexor: Boolean = false) =
        Signal(name, start, size, true, signed, 1.0, 0.0, "", mux, multiplexor)

    /** A byte that equals a checksum of the rest of the frame, in every frame, becomes a signal and is no longer free. */
    private fun checksums(message: FrameStore.Message, p: FrameStore.Payloads, free: BooleanArray, out: MutableList<Suggestion>) {
        if (p.length < 2 || p.size < 8) return
        val idSum = (message.id and 0xFF) + (message.id ushr 8 and 0xFF)
        for (at in 0 until p.length) {
            if (!(at * 8 until at * 8 + 8).all { free[it] }) continue
            var teslaSum = 0; var plainSum = 0; var xor = 0
            var varies = false
            for (k in 0 until p.size) {
                var sum = 0
                var x = 0
                for (b in 0 until p.length) if (b != at) { val v = p.data[k * p.length + b].toInt() and 0xFF; sum += v; x = x xor v }
                val actual = p.data[k * p.length + at].toInt() and 0xFF
                if (actual == (sum + idSum) and 0xFF) teslaSum++
                if (actual == sum and 0xFF) plainSum++
                if (actual == x) xor++
                if (actual != p.data[at].toInt() and 0xFF) varies = true
            }
            // a constant byte can match by accident: all the other bytes constant too
            if (!varies) continue
            val needed = p.size - p.size / 100
            val how = when {
                teslaSum >= needed -> "the sum of the other bytes and the ID (Tesla's)"
                plainSum >= needed -> "the sum of the other bytes"
                xor >= needed -> "the XOR of the other bytes"
                else -> crc(message, p, at, free, out) ?: continue
            }
            for (bit in at * 8 until at * 8 + 8) free[bit] = false
            out += Suggestion(message, field("CHECKSUM_${at * 8}", at * 8, 8), "Checksum: equals $how in every frame", 0.99)
        }
    }

    /** CRC-8 tables, most significant bit first: AUTOSAR's polynomial 2F (Tesla's E2E messages) and SAE J1850's 1D. */
    private val CRC_TABLES = listOf(0x2F, 0x1D).map { poly ->
        poly to IntArray(256) { v -> var c = v; repeat(8) { c = if (c and 0x80 != 0) (c shl 1 xor poly) and 0xFF else c shl 1 and 0xFF }; c }
    }

    /**
     * Byte [at] as a CRC-8 of the other bytes. A CRC is linear, so whatever its start value, final XOR or the hidden
     * "data id" byte mixed in, the byte XOR the zero-start CRC of the rest is a constant: one value for a fixed data id,
     * or one per counter value when the data id comes from a table the counter indexes (Tesla's E2E). The counter is
     * looked for as a free nibble the leftover value is a function of; found, it is added too. Returns the description.
     */
    private fun crc(message: FrameStore.Message, p: FrameStore.Payloads, at: Int, free: BooleanArray, out: MutableList<Suggestion>): String? {
        if (p.size < 16) return null
        val n = minOf(p.size, 4000)
        val rest = IntArray(n)
        for ((poly, table) in CRC_TABLES) {
            val leftovers = HashSet<Int>()
            // the CRCs of the rest: a byte that only follows a counter while the rest stays put would pass the test too
            val inputs = HashSet<Int>()
            for (k in 0 until n) {
                var c = 0
                for (b in 0 until p.length) if (b != at) c = table[c xor (p.data[k * p.length + b].toInt() and 0xFF)]
                inputs += c
                rest[k] = c xor (p.data[k * p.length + at].toInt() and 0xFF)
                leftovers += rest[k]
                if (leftovers.size > 16) break
            }
            if (leftovers.size > 16 || inputs.size < 32) continue
            val name = "CRC-8 (polynomial %X) of the other bytes".format(poly)
            if (leftovers.size == 1) return name
            // the nibble that picks the leftover: the alive counter indexing the data-id table
            for (nibble in 0 until p.length * 2) {
                if (nibble / 2 == at || !(nibble * 4 until nibble * 4 + 4).all { free[it] }) continue
                val byValue = HashMap<Int, Int>()
                val consistent = (0 until n).all { k ->
                    val value = p.data[k * p.length + nibble / 2].toInt() shr (nibble % 2 * 4) and 15
                    byValue.getOrPut(value) { rest[k] } == rest[k]
                }
                if (!consistent || byValue.size < 2) continue
                for (bit in nibble * 4 until nibble * 4 + 4) free[bit] = false
                out += Suggestion(message, field("COUNTER_${nibble * 4}", nibble * 4, 4), "Counter: picks the CRC's data id in byte $at (${byValue.size} values)", 0.95)
                return "$name, with a data id per counter value (Tesla's and AUTOSAR's end-to-end protection)"
            }
        }
        return null
    }

    /** Flips of each bit between consecutive frames of [rows] (all frames when null). */
    private fun flips(p: FrameStore.Payloads, rows: IntArray?): IntArray {
        val result = IntArray(p.length * 8)
        val n = rows?.size ?: p.size
        for (i in 1 until n) {
            val a = (rows?.get(i - 1) ?: (i - 1)) * p.length
            val b = (rows?.get(i) ?: i) * p.length
            for (byte in 0 until p.length) {
                var changed = (p.data[a + byte].toInt() xor p.data[b + byte].toInt()) and 0xFF
                while (changed != 0) { result[byte * 8 + Integer.numberOfTrailingZeros(changed)]++; changed = changed and (changed - 1) }
            }
        }
        return result
    }

    /** [known]: the DBC's multiplexor for this message; its pages are then decoded each by itself, and no other selector is looked for. */
    private fun layout(message: FrameStore.Message, p: FrameStore.Payloads, free: BooleanArray, known: Signal?, out: MutableList<Suggestion>) {
        val all = flips(p, null)
        val selector = known ?: multiplexor(p, free, all)
        if (selector == null) { fields(message, p, null, free, all, -1, out); return }
        // frames grouped by the selector's value, and the flips between consecutive frames of the same group
        val groups = HashMap<Long, ArrayList<Int>>()
        for (k in 0 until p.size) groups.getOrPut(selector.raw(p.data, k * p.length)) { ArrayList() } += k
        val within = IntArray(all.size)
        val perGroup = groups.mapValues { (_, rows) -> flips(p, rows.toIntArray()).also { f -> for (i in f.indices) within[i] += f[i] } }
        for (bit in selector.bits) free[bit] = false
        if (known == null) out += Suggestion(message, field("MUX_${selector.start}", selector.start, selector.size, multiplexor = true),
            "Multiplexor: the rest of the frame only makes sense per value of this field (${groups.size} values)", 0.7)
        // bits that behave the same whatever the selector says are ordinary signals; the rest belong to one value each
        val shared = BooleanArray(all.size) { free[it] && within[it] * 2 >= all[it] }
        fields(message, p, null, shared, all, -1, out)
        val own = BooleanArray(all.size) { free[it] && !shared[it] }
        for ((value, rows) in groups.entries.sortedBy { it.key }) fields(message, p, rows.toIntArray(), own, perGroup[value]!!, value.toInt(), out)
    }

    /**
     * A field of up to 8 bits with 2..16 values that changes often, where the other bits flip less than half as much
     * between frames of the same value as between consecutive frames. A plain counter fails that test: frames a whole cycle apart
     * differ at least as much as neighbours.
     */
    private fun multiplexor(p: FrameStore.Payloads, free: BooleanArray, all: IntArray): Signal? {
        for (candidate in segments(all, free)) {
            if (candidate.size > 8) continue
            val last = HashMap<Long, Int>()
            var changes = 0
            var steps = 0
            var consecutive = 0L
            var sameValue = 0L
            var previous = 0L
            for (k in 0 until p.size) {
                val value = candidate.raw(p.data, k * p.length)
                if (k > 0 && value != previous) changes++
                if (k > 0 && (value - previous) and ((1L shl candidate.size) - 1) == 1L) steps++
                previous = value
                val earlier = last.put(value, k)
                if (last.size > 16) break
                for (byte in 0 until p.length) {
                    val mask = maskOutside(candidate, byte)
                    val now = p.data[k * p.length + byte].toInt()
                    if (k > 0) consecutive += Integer.bitCount((now xor p.data[(k - 1) * p.length + byte].toInt()) and mask)
                    if (earlier != null) sameValue += Integer.bitCount((now xor p.data[earlier * p.length + byte].toInt()) and mask)
                }
            }
            // a counter also changes in every frame and repeats its values, but it counts up by one; a selector doesn't
            if (steps >= (p.size - 1) * 0.9) continue
            if (last.size in 2..16 && changes >= p.size * 0.3 && consecutive >= p.size / 2 && sameValue * 2 < consecutive) return candidate
        }
        return null
    }

    /** The bits of [byte] that are not part of [signal]. */
    private fun maskOutside(signal: Signal, byte: Int): Int {
        var mask = 0xFF
        for (bit in signal.bits) if (bit / 8 == byte) mask = mask and (1 shl (bit % 8)).inv()
        return mask
    }

    /** Runs of allowed bits that flip, split where a higher bit flips much more often than the bit below it (see [Analysis.guessFields]). */
    private fun segments(flips: IntArray, allowed: BooleanArray): List<Signal> {
        val result = ArrayList<Signal>()
        var bit = 0
        while (bit < flips.size) {
            if (flips[bit] == 0 || !allowed[bit]) { bit++; continue }
            val start = bit
            while (bit + 1 < flips.size && allowed[bit + 1] && flips[bit + 1] > 0 && flips[bit + 1] <= flips[bit] * 2.5 + 2 && bit + 1 - start < 32) bit++
            result += field("", start, bit - start + 1)
            bit++
        }
        return result
    }

    private fun fields(message: FrameStore.Message, p: FrameStore.Payloads, rows: IntArray?, allowed: BooleanArray, flips: IntArray, mux: Int, out: MutableList<Suggestion>) {
        val n = rows?.size ?: p.size
        val prefix = if (mux >= 0) "M${mux}_" else ""
        for (segment in Analysis.joinCarries(segments(flips, allowed), p, rows)) {
            val size = segment.size
            val start = segment.start
            val distinct = HashSet<Long>()
            var steps = 0
            var wraps = 0
            var signedWraps = 0
            var previous = 0L
            var highest = 0L
            // back to 0, by the value it came from: a counter that stops short of its field's top (0..14) wraps from its highest
            val wrapsFrom = HashMap<Long, Int>()
            val half = 1L shl (size - 1)
            for (i in 0 until n) {
                val value = segment.raw(p.data, (rows?.get(i) ?: i) * p.length)
                if (distinct.size <= 16) distinct += value
                if (value > highest) highest = value
                if (i > 0) {
                    if ((value - previous) and (half * 2 - 1) == 1L) steps++
                    else if (value == 0L) wrapsFrom[previous] = (wrapsFrom[previous] ?: 0) + 1
                    if (abs(value - previous) > half) wraps++
                    // the same two values read as signed
                    if (abs((if (value >= half) value - 2 * half else value) - (if (previous >= half) previous - 2 * half else previous)) > half) signedWraps++
                }
                previous = value
            }
            steps += wrapsFrom[highest] ?: 0
            out += when {
                // a counter only shows as one when every frame is there, which a multiplexed group's frames are not
                mux < 0 && size in 2..8 && n >= 8 && steps >= (n - 1) * 0.9 ->
                    Suggestion(message, field("COUNTER_$start", start, size), (if (highest < half * 2 - 1) "Counter: goes up by one in every frame, 0 to $highest" else "Counter: goes up by one in every frame"), 0.9)
                size == 1 -> Suggestion(message, field("${prefix}FLAG_$start", start, 1, mux = mux), "Flag: a single bit that changes", 0.4)
                size <= 8 && distinct.size <= 8 -> Suggestion(message, field("${prefix}STATE_$start", start, size, mux = mux), "State: takes ${distinct.size} different values", 0.4)
                else -> {
                    // jumps across the middle of the range that disappear when read as signed: a value crossing zero
                    val signed = size >= 4 && wraps > 0 && signedWraps == 0
                    Suggestion(message, field("${prefix}FIELD_$start", start, size, signed, mux), if (signed) "Measurement, signed: it crosses zero" else "Measurement: a value that varies", 0.4)
                }
            }
        }
    }

    /**
     * One decoded or guessed signal at the [GRID] moments, with mean 0 and length 1, so a dot product is a correlation;
     * and the same for its changes from one moment to the next. Two signals that only both drift, or both step once,
     * agree in value but not in when they change.
     */
    private class Vector(val values: FloatArray, val changes: FloatArray, val mean: Double, val spread: Double)

    private fun normalized(raw: DoubleArray): FloatArray? {
        val mean = raw.average()
        var spread = 0.0
        for (v in raw) spread += (v - mean) * (v - mean)
        if (spread < 1e-12) return null
        val norm = sqrt(spread)
        return FloatArray(raw.size) { ((raw[it] - mean) / norm).toFloat() }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var r = 0f
        for (k in a.indices) r += a[k] * b[k]
        return r
    }

    private fun vector(snapshots: FrameStore.Snapshots, m: Int, signal: Signal): Vector? = values(snapshots, m, signal)?.let(::vector)

    /** [signal]'s values at the grid moments; before the message's first frame, its first value. */
    private fun values(snapshots: FrameStore.Snapshots, m: Int, signal: Signal): DoubleArray? {
        val length = snapshots.length[m]
        val valid = snapshots.valid[m]
        val first = valid.indexOf(true)
        if (first < 0 || !signal.fits(length)) return null
        val data = snapshots.data[m]
        return DoubleArray(GRID) { k -> signal.physical(signal.raw(data, (if (valid[k]) k else first) * length)) }
    }

    private fun vector(raw: DoubleArray): Vector? {
        val mean = raw.average()
        var spread = 0.0
        for (k in 0 until GRID) spread += (raw[k] - mean) * (raw[k] - mean)
        // a clock changes by the same step every time: its changes have no spread and it matches nothing
        val steps = DoubleArray(GRID - 1) { raw[it + 1] - raw[it] }
        if (spread < 1e-12 || steps.count { it != 0.0 } < MIN_CHANGES) return null
        val changes = normalized(steps) ?: return null
        val norm = sqrt(spread)
        return Vector(FloatArray(GRID) { ((raw[it] - mean) / norm).toFloat() }, changes, mean, norm)
    }

    /** Renames and scales the unnamed fields that follow a decoded signal: the same quantity sent again, often on another bus. */
    private fun nameAfterKnownSignals(store: FrameStore, known: (FrameStore.Message) -> DbcMessage?, suggestions: MutableList<Suggestion>) {
        if (store.lastTime <= store.firstTime) return
        val snapshots = store.snapshots(IntArray(GRID) { (store.firstTime + (store.lastTime - store.firstTime).toLong() * it / (GRID - 1)).toInt() })
        // [how] it is derived from the signal: as it is, its rate of change (acceleration from speed) or its running total (distance from speed)
        class Reference(val message: FrameStore.Message, val signal: Signal, val vector: Vector, val how: String, val unit: String)
        val references = ArrayList<Reference>()
        val step = (store.lastTime - store.firstTime) / (GRID - 1) / FrameStore.TICKS_PER_SECOND
        for (m in snapshots.messages.indices) {
            for (signal in known(snapshots.messages[m])?.signals.orEmpty()) {
                val name = signal.name.lowercase()
                // a multiplexor only says which page a frame carries: anything else that pages through in step matches it
                if (signal.muxValue >= 0 || signal.isMultiplexor || "checksum" in name || "counter" in name || "crc" in name) continue
                val raw = values(snapshots, m, signal) ?: continue
                val message = snapshots.messages[m]
                vector(raw)?.let { references += Reference(message, signal, it, "", signal.unit) }
                val rate = DoubleArray(GRID) { k -> (raw[minOf(k + 1, GRID - 1)] - raw[minOf(k + 1, GRID - 1) - 1]) / step }
                vector(rate)?.let { references += Reference(message, signal, it, "RATE_", signal.unit + "/s") }
                var sum = 0.0
                vector(DoubleArray(GRID) { k -> sum += raw[k] * step; sum })?.let { references += Reference(message, signal, it, "TOTAL_", signal.unit + "·s") }
            }
        }
        if (references.isEmpty()) return
        for (i in suggestions.indices) {
            val suggestion = suggestions[i]
            val field = suggestion.signal
            if (suggestion.confidence > 0.5 || field.muxValue >= 0 || suggestion.message.index >= snapshots.messages.size) continue
            val mine = vector(snapshots, suggestion.message.index, field) ?: continue
            var best: Reference? = null
            var bestR = 0f
            for (reference in references) {
                // its own message, or that id on another bus: a field that repeats a signal of the same frame says nothing new;
                // a single bit can't stand for a signal of several bits
                if (reference.message.id == suggestion.message.id || (field.size == 1 && reference.signal.size > 1)) continue
                val r = dot(mine.values, reference.vector.values)
                // a rate is already a change, and its own changes are mostly rounding noise: it is held to the value test alone
                if (abs(r) <= abs(bestR) || (reference.how != "RATE_" && dot(mine.changes, reference.vector.changes) * Math.signum(r) < MATCH_CHANGES)) continue
                bestR = r; best = reference
            }
            if (best == null || abs(bestR) < MATCH) continue
            // a flag or a state is the same values as the signal it follows, not a scaled copy: kept raw, with its names when they match
            val state = best.how == "" && (field.size <= 4 || best.signal.size <= 4 || best.signal.valueNames.isNotEmpty())
            val fitted = bestR * best.vector.spread / mine.spread
            val (factor, offset) = if (state) 1.0 to 0.0 else Analysis.tidy(fitted, best.vector.mean - fitted * mine.mean)
            val signal = Signal("LIKE_${best.how}${best.signal.name}_${field.start}", field.start, field.size, true, field.signed, factor, offset, if (state) "" else best.unit, -1, false)
            if (state && bestR > 0 && abs(best.signal.factor - 1.0) < 1e-9 && best.signal.offset == 0.0) signal.valueNames = best.signal.valueNames
            val what = when (best.how) { "RATE_" -> "the rate of change of "; "TOTAL_" -> "the running total of "; else -> "" }
            suggestions[i] = Suggestion(suggestion.message, signal,
                "Moves with $what${best.signal.name} (bus ${best.message.bus}, ${"%X".format(best.message.id)}), r = ${"%.3f".format(abs(bestR))}; scaled to its units", abs(bestR).toDouble())
        }
    }

    /** The last word of a marker note that says which half of an action it was. */
    private val HALF = Regex("\\s+(on|off|in|out|open|opened|close|closed|up|down|start|stop|pressed|released|lock|locked|unlock|unlocked)$", RegexOption.IGNORE_CASE)

    /** Bits that changed around every one of [times] (1 s before to 2 s after) and at no other time. */
    private fun changedOnlyAt(store: FrameStore, known: (FrameStore.Message) -> DbcMessage?, times: List<Int>, note: String, out: MutableList<Suggestion>) {
        val second = FrameStore.TICKS_PER_SECOND.toInt()
        val name = "EVT_" + note.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_').take(24).ifEmpty { "MARKER" }
        val windows = times.map { store.bitActivity(it - second, it + 2 * second) }
        for (m in windows[0].indices) {
            val activity = windows[0][m] ?: continue
            val message = activity.message
            val taken = BooleanArray(activity.inside.size)
            known(message)?.signals?.forEach { signal -> for (bit in signal.bits) if (bit in taken.indices) taken[bit] = true }
            // a window's flips inside and outside add up to all of them: what is left after every window is "elsewhere"
            fun only(bit: Int) = !taken[bit] && windows.all { (it[m]?.inside?.get(bit) ?: 0) > 0 } &&
                activity.inside[bit] + activity.outside[bit] - windows.sumOf { it[m]?.inside?.get(bit) ?: 0 } <= 0
            var bit = 0
            while (bit < message.data.size * 8) {
                if (!only(bit)) { bit++; continue }
                val start = bit
                while (bit + 1 < message.data.size * 8 && only(bit + 1)) bit++
                bit++
                // takes its bits out of the plain guesses; what a guess has below or above them stays a field
                val overlapped = out.filter { it.message === message && it.confidence < 0.5 && it.signal.bits.any { b -> b in start until bit } }
                out.removeAll(overlapped)
                for (old in overlapped) {
                    val guess = old.signal
                    val end = guess.start + guess.size
                    if (guess.start < start) out += Suggestion(message, field(guess.name, guess.start, start - guess.start, guess.signed, guess.muxValue), old.evidence, old.confidence)
                    if (end > bit) out += Suggestion(message, field(guess.name.substringBeforeLast('_') + "_" + bit, bit, end - bit, mux = guess.muxValue), old.evidence, old.confidence)
                }
                out += Suggestion(message, field("${name}_$start", start, bit - start),
                    if (times.size == 1) "Changed only at the marker \"$note\"" else "Changed at all ${times.size} \"$note\" markers and at no other time",
                    if (times.size == 1) 0.8 else 0.95)
            }
        }
    }
}
