package com.ehoeve.canpilot.core

import kotlin.math.abs
import kotlin.math.sqrt

/** A signal the automatic decoder proposes, why, and how sure it is (0..1). Meant to be reviewed, not trusted. */
class Suggestion(val message: FrameStore.Message, val signal: Signal, val evidence: String, val confidence: Double)

/**
 * Decodes what can be decoded without a person: the layout of every message (fields, counters, verified checksums,
 * multiplexing), then names for the fields that follow an already decoded signal or changed only at a marker.
 */
object AutoDecode {
    private const val GRID = 400
    private const val MATCH = 0.95
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
            known(message)?.signals?.forEach { signal -> for (bit in signal.bits) if (bit in free.indices) free[bit] = false }
            checksums(message, payloads, free, result)
            layout(message, payloads, free, result)
        }
        // markers first: they cut their bits out of the guessed fields, and what is left is then compared
        if (markers.isNotEmpty()) progress("Looking at what changed at ${markers.size} markers")
        for ((time, note) in markers) changedOnlyAt(store, known, time, note, result)
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
                else -> continue
            }
            for (bit in at * 8 until at * 8 + 8) free[bit] = false
            out += Suggestion(message, field("CHECKSUM_${at * 8}", at * 8, 8), "Checksum: equals $how in every frame", 0.99)
        }
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

    private fun layout(message: FrameStore.Message, p: FrameStore.Payloads, free: BooleanArray, out: MutableList<Suggestion>) {
        val all = flips(p, null)
        val selector = multiplexor(p, free, all)
        if (selector == null) { fields(message, p, null, free, all, -1, out); return }
        // frames grouped by the selector's value, and the flips between consecutive frames of the same group
        val groups = HashMap<Long, ArrayList<Int>>()
        for (k in 0 until p.size) groups.getOrPut(selector.raw(p.data, k * p.length)) { ArrayList() } += k
        val within = IntArray(all.size)
        val perGroup = groups.mapValues { (_, rows) -> flips(p, rows.toIntArray()).also { f -> for (i in f.indices) within[i] += f[i] } }
        for (bit in selector.bits) free[bit] = false
        out += Suggestion(message, field("MUX_${selector.start}", selector.start, selector.size, multiplexor = true),
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
        for (segment in segments(flips, allowed)) {
            val size = segment.size
            val start = segment.start
            val distinct = HashSet<Long>()
            var steps = 0
            var wraps = 0
            var signedWraps = 0
            var previous = 0L
            val half = 1L shl (size - 1)
            for (i in 0 until n) {
                val value = segment.raw(p.data, (rows?.get(i) ?: i) * p.length)
                if (distinct.size <= 16) distinct += value
                if (i > 0) {
                    if ((value - previous) and (half * 2 - 1) == 1L) steps++
                    if (abs(value - previous) > half) wraps++
                    // the same two values read as signed
                    if (abs((if (value >= half) value - 2 * half else value) - (if (previous >= half) previous - 2 * half else previous)) > half) signedWraps++
                }
                previous = value
            }
            out += when {
                // a counter only shows as one when every frame is there, which a multiplexed group's frames are not
                mux < 0 && size in 2..8 && n >= 8 && steps >= (n - 1) * 0.9 ->
                    Suggestion(message, field("COUNTER_$start", start, size), "Counter: goes up by one in every frame", 0.9)
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

    private fun vector(snapshots: FrameStore.Snapshots, m: Int, signal: Signal): Vector? {
        val length = snapshots.length[m]
        val valid = snapshots.valid[m]
        val first = valid.indexOf(true)
        if (first < 0 || !signal.fits(length)) return null
        val data = snapshots.data[m]
        val raw = DoubleArray(GRID)
        var mean = 0.0
        // before the message's first frame: its first value
        for (k in 0 until GRID) { raw[k] = signal.physical(signal.raw(data, (if (valid[k]) k else first) * length)); mean += raw[k] }
        mean /= GRID
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
        class Reference(val message: FrameStore.Message, val signal: Signal, val vector: Vector)
        val references = ArrayList<Reference>()
        for (m in snapshots.messages.indices) {
            for (signal in known(snapshots.messages[m])?.signals.orEmpty()) {
                val name = signal.name.lowercase()
                if (signal.muxValue >= 0 || "checksum" in name || "counter" in name || "crc" in name) continue
                vector(snapshots, m, signal)?.let { references += Reference(snapshots.messages[m], signal, it) }
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
                if (abs(r) <= abs(bestR) || dot(mine.changes, reference.vector.changes) * Math.signum(r) < MATCH_CHANGES) continue
                bestR = r; best = reference
            }
            if (best == null || abs(bestR) < MATCH) continue
            val factor = bestR * best.vector.spread / mine.spread
            val signal = Signal("LIKE_${best.signal.name}_${field.start}", field.start, field.size, true, field.signed, factor, best.vector.mean - factor * mine.mean, best.signal.unit, -1, false)
            suggestions[i] = Suggestion(suggestion.message, signal,
                "Moves with ${best.signal.name} (bus ${best.message.bus}, ${"%X".format(best.message.id)}), r = ${"%.3f".format(abs(bestR))}; scaled to its units", abs(bestR).toDouble())
        }
    }

    /** Bits that flipped from 1 s before to 2 s after [time] and at no other moment get a signal named after the marker's [note]. */
    private fun changedOnlyAt(store: FrameStore, known: (FrameStore.Message) -> DbcMessage?, time: Int, note: String, out: MutableList<Suggestion>) {
        val second = FrameStore.TICKS_PER_SECOND.toInt()
        val name = "EVT_" + note.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_').take(24).ifEmpty { "MARKER" }
        for (activity in store.bitActivity(time - second, time + 2 * second)) {
            if (activity == null) continue
            val message = activity.message
            val taken = BooleanArray(activity.inside.size)
            known(message)?.signals?.forEach { signal -> for (bit in signal.bits) if (bit in taken.indices) taken[bit] = true }
            var bit = 0
            while (bit < message.data.size * 8) {
                if (taken[bit] || activity.inside[bit] == 0 || activity.outside[bit] != 0) { bit++; continue }
                val start = bit
                while (bit + 1 < message.data.size * 8 && !taken[bit + 1] && activity.inside[bit + 1] > 0 && activity.outside[bit + 1] == 0) bit++
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
                out += Suggestion(message, field("${name}_$start", start, bit - start), "Changed only at the marker \"$note\"", 0.8)
            }
        }
    }
}
