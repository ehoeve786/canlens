package com.ehoeve.canlens.core

import kotlin.math.abs
import kotlin.math.sqrt

/** A bit range found by a search, as a ready signal (scaled where the search could tell), and how well it fits. */
class FieldMatch(val message: FrameStore.Message, val signal: Signal, val score: Double)

/** Searches for where a signal lives, and guesses at the layout of an unknown message. */
object Analysis {
    private val NICE_FACTORS = doubleArrayOf(1.0, 0.1, 0.01, 0.001, 0.5, 0.25, 0.125, 0.0625, 0.05, 0.02, 2.0, 10.0)
    private val NICE_OFFSETS = doubleArrayOf(0.0, -40.0)
    private const val MAX_FIELD = 16

    /**
     * Fields worth trying in a payload of [length] bytes: little-endian ones of 1..16 bits and big-endian ones of
     * 9..16 bits whose most significant bit is marked in [top] (a wider field with a dead top bit says nothing new),
     * signed as well from 8 bits up. With [trimLow] the least significant bit must be marked too: a field padded with
     * a dead low bit is the same field at twice the factor.
     */
    private fun candidates(length: Int, top: BooleanArray, trimLow: Boolean): List<Signal> {
        val result = ArrayList<Signal>()
        fun add(start: Int, size: Int, little: Boolean) {
            val unsigned = Signal("", start, size, little, false, 1.0, 0.0, "", -1, false)
            if (!unsigned.fits(length) || (trimLow && !top[unsigned.bits[0]])) return
            result += unsigned
            if (size >= 8) result += Signal("", start, size, little, true, 1.0, 0.0, "", -1, false)
        }
        for (bit in 0 until length * 8) {
            if (!top[bit]) continue
            for (size in 1..minOf(MAX_FIELD, bit + 1)) add(bit - size + 1, size, true)
            for (size in 9..MAX_FIELD) add(bit, size, false)
        }
        return result
    }

    /**
     * A fitted factor and offset as a DBC would have them: a factor within 3 % of a round one (1, 2, 2.5 or 5 times a
     * power of ten, either sign) becomes that, and then an offset within one step of a whole number becomes it. A fit
     * comes out a little off because the samples are; the real encoding almost never is.
     */
    fun tidy(factor: Double, offset: Double): Pair<Double, Double> {
        if (factor == 0.0 || factor.isNaN()) return factor to offset
        val magnitude = Math.pow(10.0, Math.floor(Math.log10(abs(factor))))
        val round = doubleArrayOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude * Math.signum(factor) }.minBy { abs(it - factor) }
        if (abs(round - factor) > abs(round) * 0.03) return factor to offset
        val whole = Math.rint(offset)
        return round to (if (abs(offset - whole) <= abs(round)) whole else offset)
    }

    private fun named(found: Signal, factor: Double, offset: Double) =
        Signal("FOUND_${found.start}_${found.size}", found.start, found.size, found.littleEndian, found.signed, factor, offset, "", -1, false)

    /** Best first, at most [limit], and no two overlapping fields of the same message (the weaker one is a part or a superset of the better). */
    private fun best(found: ArrayList<FieldMatch>, limit: Int, better: Comparator<FieldMatch>): List<FieldMatch> {
        val picked = ArrayList<FieldMatch>()
        for (match in found.sortedWith(better)) {
            if (picked.none { it.message === match.message && it.signal.bits.any { bit -> bit in match.signal.bits } }) picked += match
            if (picked.size == limit) break
        }
        return picked
    }

    /**
     * Fields whose value was [points]' value at [points]' time (store time), within [tolerance]. One point, or the
     * same value every time: tried with common factors. Different values: any factor that fits them all, and from
     * three points on any factor and offset.
     */
    fun findByValue(store: FrameStore, points: List<Pair<Int, Double>>, tolerance: Double, limit: Int = 50): List<FieldMatch> {
        if (points.isEmpty()) return emptyList()
        val sorted = points.sortedBy { it.first }
        val snapshots = store.snapshots(IntArray(sorted.size) { sorted[it].first })
        val wanted = DoubleArray(sorted.size) { sorted[it].second }
        val sameValue = wanted.all { abs(it - wanted[0]) <= tolerance }
        val found = ArrayList<FieldMatch>()
        val raw = DoubleArray(wanted.size)
        for (m in snapshots.messages.indices) {
            val length = snapshots.length[m]
            if (length == 0 || !snapshots.valid[m].all { it }) continue
            val data = snapshots.data[m]
            // top bits worth trying: set at some point, or different between the points
            val top = BooleanArray(length * 8) { bit -> (0 until wanted.size).any { k -> data[k * length + bit / 8].toInt() shr (bit % 8) and 1 == 1 } }
            for (candidate in candidates(length, top, false)) {
                for (k in wanted.indices) raw[k] = candidate.physical(candidate.raw(data, k * length))
                var factor = 0.0
                var offset = 0.0
                var error = Double.MAX_VALUE
                fun attempt(f: Double, o: Double) {
                    var worst = 0.0
                    for (k in wanted.indices) worst = maxOf(worst, abs(raw[k] * f + o - wanted[k]))
                    if (worst < error && f != 0.0) { error = worst; factor = f; offset = o }
                }
                if (sameValue) {
                    // offset −40 too: how temperatures are usually sent
                    for (o in NICE_OFFSETS) for (f in NICE_FACTORS) { attempt(f, o); if (error <= tolerance) break }
                } else {
                    var rr = 0.0; var rv = 0.0; var sumR = 0.0; var sumV = 0.0
                    for (k in wanted.indices) { rr += raw[k] * raw[k]; rv += raw[k] * wanted[k]; sumR += raw[k]; sumV += wanted[k] }
                    if (rr > 0) attempt(rv / rr, 0.0)
                    val n = wanted.size
                    val spread = rr - sumR * sumR / n
                    if (error > tolerance && n >= 3 && spread > 1e-9) {
                        val f = (rv - sumR * sumV / n) / spread
                        attempt(f, (sumV - f * sumR) / n)
                    }
                }
                if (error <= tolerance) found += FieldMatch(snapshots.messages[m], named(candidate, factor, offset), error)
            }
        }
        // equally good fits: the narrowest, which is the one without dead low bits
        return best(found, limit, compareBy<FieldMatch> { (it.score / tolerance * 10).toInt() }.thenBy { it.signal.size })
    }

    /**
     * Fields that move with [reference] (times in seconds, as [FrameStore.series] gives them), by correlation over
     * [samples] evenly spaced moments. Each result is scaled to the reference's units; its score is |r|.
     */
    fun findCorrelated(store: FrameStore, reference: FrameStore.Series, exclude: FrameStore.Message?, samples: Int = 400, minimum: Double = 0.7, limit: Int = 40): List<FieldMatch> {
        if (reference.size < 2) return emptyList()
        val from = maxOf(store.firstTime, (reference.times[0] * FrameStore.TICKS_PER_SECOND).toInt())
        val to = minOf(store.lastTime, (reference.times[reference.size - 1] * FrameStore.TICKS_PER_SECOND).toInt())
        if (to <= from) return emptyList()
        val times = IntArray(samples) { (from + (to - from).toLong() * it / (samples - 1)).toInt() }
        val snapshots = store.snapshots(times)
        // the reference, held until its next sample, at the same moments
        val y = DoubleArray(samples)
        var at = 0
        for (k in 0 until samples) {
            val t = times[k] / FrameStore.TICKS_PER_SECOND
            while (at + 1 < reference.size && reference.times[at + 1] <= t) at++
            y[k] = reference.values[at].toDouble()
        }
        val found = ArrayList<FieldMatch>()
        val x = DoubleArray(samples)
        for (m in snapshots.messages.indices) {
            val length = snapshots.length[m]
            val valid = snapshots.valid[m]
            val first = valid.indexOf(true)
            if (length == 0 || first < 0 || snapshots.messages[m] === exclude) continue
            val n = valid.count { it }
            if (n < 20) continue
            val data = snapshots.data[m]
            var meanY = 0.0
            for (k in 0 until samples) if (valid[k]) meanY += y[k]
            meanY /= n
            var spreadY = 0.0
            for (k in 0 until samples) if (valid[k]) spreadY += (y[k] - meanY) * (y[k] - meanY)
            if (spreadY < 1e-12) continue
            val top = BooleanArray(length * 8) { bit ->
                val reference = data[first * length + bit / 8].toInt() shr (bit % 8) and 1
                (first + 1 until samples).any { k -> valid[k] && data[k * length + bit / 8].toInt() shr (bit % 8) and 1 != reference }
            }
            for (candidate in candidates(length, top, true)) {
                var meanX = 0.0
                for (k in 0 until samples) if (valid[k]) { x[k] = candidate.physical(candidate.raw(data, k * length)); meanX += x[k] }
                meanX /= n
                var spreadX = 0.0
                var together = 0.0
                for (k in 0 until samples) if (valid[k]) { val d = x[k] - meanX; spreadX += d * d; together += d * (y[k] - meanY) }
                if (spreadX < 1e-12) continue
                val r = together / sqrt(spreadX * spreadY)
                if (abs(r) < minimum) continue
                val (factor, offset) = tidy(together / spreadX, meanY - together / spreadX * meanX)
                found += FieldMatch(snapshots.messages[m], named(candidate, factor, offset), abs(r))
            }
        }
        return best(found, limit, compareByDescending<FieldMatch> { it.score }.thenByDescending { it.signal.size })
    }

    /**
     * A guess at the fields of [message] from how often each bit flips: inside a little-endian field a higher bit
     * rarely flips much more often than the bit below it, so a jump upward (more than 2.5 times) starts a new field. Fields that count up
     * by one are named COUNTER, a byte whose bits all flip about half the time CHECKSUM, the rest FIELD.
     * Big-endian fields come out as one field per byte.
     */
    fun guessFields(store: FrameStore, message: FrameStore.Message): List<Signal> {
        val activity = store.bitActivity(only = message).getOrNull(message.index) ?: return emptyList()
        val flips = activity.inside
        val bits = message.data.size * 8
        val segments = ArrayList<Signal>()
        var bit = 0
        while (bit < bits) {
            if (flips[bit] == 0) { bit++; continue }
            val start = bit
            while (bit + 1 < bits && flips[bit + 1] > 0 && flips[bit + 1] <= flips[bit] * 2.5 + 2 && bit + 1 - start < 32) bit++
            segments += Signal("", start, bit - start + 1, true, false, 1.0, 0.0, "", -1, false)
            bit++
        }
        val result = ArrayList<Signal>()
        for (segment in joinCarries(segments, store.payloads(message), null)) {
            val start = segment.start
            val size = segment.size
            val plain = Signal("FIELD_$start", start, size, true, false, 1.0, 0.0, "", -1, false)
            val series = store.series(message, DbcMessage(message.id, "", 0, listOf(plain)), plain)
            var steps = 0
            // a counter may wrap from below its field's top (0..14): from the highest value seen back to 0 counts as a step
            val highest = (0 until series.size).maxOfOrNull { series.values[it] } ?: 0f
            if (size in 2..8) for (i in 1 until series.size) if ((series.values[i] - series.values[i - 1]).toInt() and ((1 shl size) - 1) == 1 || (series.values[i] == 0f && series.values[i - 1] == highest)) steps++
            val counter = size in 2..8 && series.size >= 8 && steps >= (series.size - 1) * 0.9
            val checksum = !counter && size == 8 && start % 8 == 0 && activity.frames >= 8 &&
                (start until start + 8).all { flips[it] in (activity.frames * 0.3).toInt()..(activity.frames * 0.7).toInt() }
            result += if (counter || checksum) Signal((if (counter) "COUNTER_" else "CHECKSUM_") + start, start, size, true, false, 1.0, 0.0, "", -1, false) else plain
        }
        return result
    }

    /**
     * Joins neighbouring little-endian fields that are one value: whenever the upper one changes, the lower one
     * wraps around (a carry), so read together they move by a small step. Flip rates alone split a value where a
     * byte's top bits change rarely (cabin humidity in 464 starts two bits into a byte).
     */
    internal fun joinCarries(fields: List<Signal>, p: FrameStore.Payloads, rows: IntArray?): List<Signal> {
        val n = rows?.size ?: p.size
        val result = ArrayList<Signal>()
        for (next in fields) {
            val low = result.lastOrNull()
            if (low != null && low.start + low.size == next.start && low.size + next.size <= 32) {
                val joined = Signal("", low.start, low.size + next.size, true, false, 1.0, 0.0, "", next.muxValue, false)
                var changes = 0
                var carries = 0
                for (i in 1 until n) {
                    val a = (rows?.get(i - 1) ?: (i - 1)) * p.length
                    val b = (rows?.get(i) ?: i) * p.length
                    if (next.raw(p.data, a) == next.raw(p.data, b)) continue
                    changes++
                    if (abs(joined.raw(p.data, b) - joined.raw(p.data, a)) < (1L shl low.size) / 2) carries++
                }
                if (changes >= 3 && carries >= changes * 0.9) { result[result.size - 1] = joined; continue }
            }
            result += next
        }
        return result
    }

    /**
     * How each byte of [p] moves, as one character: ↑ mostly up (a counter or something rising), ↓ mostly down,
     * ⇄ between two values, ↕ up and down now and then (a measurement), ~ in most frames (noise, a checksum), blank never.
     */
    fun byteTrends(p: FrameStore.Payloads): String = buildString {
        for (b in 0 until p.length) {
            var up = 0
            var down = 0
            val values = HashSet<Int>()
            for (k in 0 until p.size) {
                val v = p.data[k * p.length + b].toInt() and 0xFF
                if (values.size <= 2) values += v
                if (k == 0) continue
                // a step across the wrap counts the short way round: 255 to 0 is one up
                val step = ((v - (p.data[(k - 1) * p.length + b].toInt() and 0xFF)) + 384) % 256 - 128
                if (step > 0) up++ else if (step < 0) down++
            }
            val changes = up + down
            append(when {
                changes == 0 -> ' '
                values.size <= 2 -> '⇄'
                up >= changes * 0.8 -> '↑'
                down >= changes * 0.8 -> '↓'
                changes * 2 > p.size -> '~'
                else -> '↕'
            })
        }
    }

    /** How often each value occurs: every value when there are at most 16 different ones, else [bins] equal ranges. Label, count. */
    /** A bit of another message and how well its flips line up with the chosen bit's (1 = always together, never apart). */
    class SimilarBit(val message: FrameStore.Message, val bit: Int, val score: Double)

    /**
     * Bits anywhere in [store] that flip when bit [bit] of [message] flips, within [window] ticks, and rarely at
     * other times: the same event seen by another module, or on another bus. Scored by flips together over all flips.
     */
    fun similarBits(store: FrameStore, message: FrameStore.Message, bit: Int, window: Int = 3000, limit: Int = 20): List<SimilarBit> {
        val known = store.messages
        val times = ArrayList<Int>()
        val previous = Array(known.size) { ByteArray(FrameStore.MAX_PAYLOAD) }
        val previousLength = IntArray(known.size)
        // first the chosen bit's flips, then every other bit's against them
        fun walk(visit: (m: Int, b: Int, time: Int) -> Unit) {
            previousLength.fill(0)
            for (chunk in store.chunks) for (i in 0 until chunk.size) {
                val m = chunk.message[i].toInt() and 0xFFFF
                if (m >= known.size) continue
                val start = chunk.start(i)
                val length = chunk.end[i] - start
                val last = previous[m]
                for (byte in 0 until minOf(length, previousLength[m])) {
                    var changed = (chunk.blob[start + byte].toInt() xor last[byte].toInt()) and 0xFF
                    while (changed != 0) { val b = Integer.numberOfTrailingZeros(changed); visit(m, byte * 8 + b, chunk.time[i]); changed = changed and (changed - 1) }
                }
                System.arraycopy(chunk.blob, start, last, 0, length)
                previousLength[m] = length
            }
        }
        walk { m, b, time -> if (m == message.index && b == bit) times += time }
        if (times.size < 2) return emptyList()
        val together = HashMap<Long, Int>()
        val all = HashMap<Long, Int>()
        walk { m, b, time ->
            if (m == message.index && b == bit) return@walk
            val key = m.toLong() shl 16 or b.toLong()
            all[key] = (all[key] ?: 0) + 1
            var at = times.binarySearch(time)
            if (at < 0) at = -at - 1
            if ((at < times.size && times[at] - time <= window) || (at > 0 && time - times[at - 1] <= window)) together[key] = (together[key] ?: 0) + 1
        }
        return together.entries.filter { it.value >= 2 }
            .map { (key, hits) -> SimilarBit(known[(key shr 16).toInt()], (key and 0xFFFF).toInt(), minOf(hits, times.size).toDouble() / (all[key]!! + times.size - minOf(hits, times.size))) }
            .filter { it.score >= 0.3 }.sortedByDescending { it.score }.take(limit)
    }

    /** Lowest, highest and average value of [series] between [from] and [to] (seconds), and how often it changed; null without samples. */
    fun stats(series: FrameStore.Series, from: Float, to: Float): FloatArray? {
        var n = 0
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        var sum = 0.0
        var changes = 0
        for (i in 0 until series.size) {
            if (series.times[i] !in from..to) continue
            val v = series.values[i]
            if (n > 0 && v != series.values[i - 1]) changes++
            n++; sum += v
            if (v < low) low = v
            if (v > high) high = v
        }
        return if (n == 0) null else floatArrayOf(low, high, (sum / n).toFloat(), changes.toFloat())
    }

    /** A value (or a range's start), how often it occurs, and when it was first seen (seconds; NaN for a range). */
    class Bar(val label: String, val count: Int, val first: Float)

    fun histogram(series: FrameStore.Series, bins: Int = 20): List<Bar> {
        if (series.size == 0) return emptyList()
        val counts = HashMap<Float, Int>()
        val first = HashMap<Float, Float>()
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        for (i in 0 until series.size) {
            val v = series.values[i]
            if (counts.size <= 16) { counts[v] = (counts[v] ?: 0) + 1; first.putIfAbsent(v, series.times[i]) }
            if (v < low) low = v
            if (v > high) high = v
        }
        fun label(v: Float) = if (v == Math.rint(v.toDouble()).toFloat() && abs(v) < 1e7f) v.toInt().toString() else "%.3g".format(v)
        if (counts.size <= 16) return counts.entries.sortedBy { it.key }.map { Bar(label(it.key), it.value, first[it.key]!!) }
        val binned = IntArray(bins)
        val width = (high - low) / bins
        for (i in 0 until series.size) binned[((series.values[i] - low) / width).toInt().coerceIn(0, bins - 1)]++
        return List(bins) { Bar(label(low + it * width), binned[it], Float.NaN) }
    }
}

/** Per message, how many frames there were and in how many each bit was set. Fed with a whole log, to compare two logs. */
class BitSummary : FrameSink {
    private class Entry { var frames = 0; val ones = IntArray(FrameStore.MAX_PAYLOAD * 8) }

    private val entries = HashMap<Long, Entry>()

    override fun frame(timeMicros: Long, bus: Int, id: Int, data: ByteArray, offset: Int, length: Int): Boolean {
        val entry = entries.getOrPut(bus.toLong() shl 32 or (id.toLong() and 0xFFFFFFFFL)) { Entry() }
        entry.frames++
        for (b in 0 until minOf(length, FrameStore.MAX_PAYLOAD)) {
            var set = data[offset + b].toInt() and 0xFF
            while (set != 0) { entry.ones[b * 8 + Integer.numberOfTrailingZeros(set)]++; set = set and (set - 1) }
        }
        return true
    }

    /**
     * For each of the first [bits] bits of a message, against [other]: [DIFFERENT] when it is constant in both logs
     * with different values, [VARIES_IN_ONE] when it changes in one log and never in the other, else 0. Null when
     * either log doesn't have the message.
     */
    fun differences(other: BitSummary, bus: Int, id: Int, bits: Int): IntArray? {
        val key = bus.toLong() shl 32 or (id.toLong() and 0xFFFFFFFFL)
        val mine = entries[key] ?: return null
        val theirs = other.entries[key] ?: return null
        fun state(e: Entry, bit: Int) = if (e.ones[bit] == 0) 0 else if (e.ones[bit] == e.frames) 1 else 2
        return IntArray(bits) { bit ->
            val a = state(mine, bit)
            val b = state(theirs, bit)
            if (a == b) 0 else if (a == 2 || b == 2) VARIES_IN_ONE else DIFFERENT
        }
    }

    companion object {
        const val DIFFERENT = 1
        const val VARIES_IN_ONE = 2
    }
}
