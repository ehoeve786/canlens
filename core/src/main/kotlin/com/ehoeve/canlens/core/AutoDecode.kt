package com.ehoeve.canlens.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A signal the automatic decoder proposes, why, and how sure it is (0..1). Meant to be reviewed, not trusted.
 * [messageName]: what to call the message when the DBC doesn't have it yet (NEW_<id> when null). [at]: the store time it is about, for an event.
 */
class Suggestion(val message: FrameStore.Message, val signal: Signal, val evidence: String, val confidence: Double, val messageName: String? = null, val at: Int? = null)

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
        val messages = store.messages
        val asked = messages.filterTo(HashSet()) { message -> message.bus < 128 && message.data.isNotEmpty() && community.any { message.id in it.second.messages } }
        val ids = asked.mapTo(HashSet()) { it.id }
        val found = arrayOfNulls<List<Suggestion>>(messages.size)
        // an id's messages on all buses are loaded together, as they are compared (as in [run])
        for (batch in Analysis.batches(store, messages.filter { it.id in ids }.groupBy { it.id }.values.toList())) {
            val loaded = Analysis.payloads(store, batch.flatten())
            for (group in batch) {
                for (message in group) if (message in asked) found[message.index] = fromCommunity(message, group, loaded, known(message), community)
                for (message in group) loaded.remove(message.index)
            }
        }
        return found.flatMap { it.orEmpty() }
    }

    /** What [community] knows about [message], whose frames and those of the rest of its [group] are [loaded]; [mine] is its DBC entry. */
    private fun fromCommunity(message: FrameStore.Message, group: List<FrameStore.Message>, loaded: Map<Int, FrameStore.Payloads>, mine: DbcMessage?, community: List<Pair<String, Dbc>>): List<Suggestion> {
        val result = ArrayList<Suggestion>()
        val frames = loaded.getValue(message.index)
        // the same frames on a lower bus too (the party bus is seen on 0 and 2): suggested there only
        if (group.any { it.bus < message.bus && copies(frames, loaded.getValue(it.index)) }) return result
        val taken = HashSet<Int>().apply { mine?.signals?.forEach { addAll(it.bits.asList()) } }
        // the first DBC that knows the message speaks for it: two files naming the same bits differently would only double the list
        var answered = false
        for ((source, dbc) in community) {
            if (answered) break
            val theirs = dbc.messages[message.id]?.takeIf { it.length == message.data.size } ?: continue
            // Model3CAN names its messages ID<hex><name>
            val name = theirs.name.removePrefix("ID%X".format(message.id)).ifEmpty { theirs.name }
            // the frames have the last word: a signal they contradict is listed unticked with the reason, and a flag
            // that is really part of a counter puts the whole layout in doubt (another model's message under this id)
            val misfits = theirs.signals.filter { it.fits(message.data.size) }.mapNotNull { signal -> misfit(signal, frames)?.let { signal to it } }
            val layout = misfits.firstOrNull { it.second.startsWith("flips") }
            // a page the selector never shows has nothing to check it against (DIF_debug: a hundred signals, one page sent)
            val pages = theirs.multiplexor?.takeIf { it.fits(message.data.size) }?.let { mux -> (0 until frames.size).mapTo(HashSet()) { mux.raw(frames.data, it * frames.length).toInt() } }
            for (signal in theirs.signals) {
                if (!signal.fits(message.data.size) || signal.bits.any { it in taken } || result.any { it.signal.name == signal.name } || signal.muxValue >= 0 && pages != null && signal.muxValue !in pages) continue
                val why = misfits.firstOrNull { it.first === signal }?.second ?: layout?.let { "${it.first.name} ${it.second}, so this layout is not this car's" }
                result += Suggestion(message, signal, "From $source ($name)" + (why?.let { " · doesn't fit: $it" } ?: ""), if (why == null) 0.75 else 0.3, name)
                answered = true
            }
        }
        return result
    }

    /** A marker note that is a number read off the car: "range=312", "soc = 78.5", "temp = -3,5" (a decimal comma, and − or – for minus too). */
    val READING = Regex("""^(.+?)\s*=\s*([-−–]?\d+(?:[.,]\d+)?)$""")

    /** The name and value of a [READING] note, or null when it isn't one. */
    fun reading(note: String): Pair<String, Double>? = READING.find(note)?.let { match ->
        match.groupValues[1] to match.groupValues[2].replace('−', '-').replace('–', '-').replace(',', '.').toDouble()
    }

    /**
     * Fields that carry the numbers the user read off the car's screen: [readings] has, by name, the moments and the
     * values. Five readings of at least four different values are asked for, since any field fits two or three; a
     * field counts when a factor and offset bring it within 2 % of the readings' span at every one of them.
     * Readings that only rise or only fall, in step with the clock (a charge, a trip), are fitted just as well by any
     * field that trends with time (an odometer, an energy counter): then a field only counts when a round factor and
     * a whole offset fit, as the car's own encoding would, and it is listed as less sure.
     */
    fun fromReadings(store: FrameStore, known: (FrameStore.Message) -> DbcMessage?, readings: Map<String, List<Pair<Int, Double>>>): List<Suggestion> =
        readings.filter { (_, points) -> points.size >= 5 && points.map { it.second }.distinct().size >= 4 }.flatMap { (name, points) ->
            val sorted = points.sortedBy { it.first }
            val wanted = DoubleArray(sorted.size) { sorted[it].second }
            val tolerance = (wanted.max() - wanted.min()) * 0.02
            val clockLike = followsTheClock(sorted, tolerance)
            // the best three once the fields a DBC already has are left out (a limit before that could leave none)
            val matches = Analysis.findByValue(store, points, tolerance, Int.MAX_VALUE)
                .filter { match -> known(match.message)?.signals.orEmpty().none { signal -> signal.bits.any { it in match.signal.bits } } }.take(3)
            // the fields' raw values at the readings, to check the rounded scale against
            val snapshots = if (matches.isEmpty()) null else store.snapshots(IntArray(sorted.size) { sorted[it].first })
            matches.mapNotNull { match ->
                val found = match.signal
                val m = match.message.index
                val plain = Signal("", found.start, found.size, found.littleEndian, found.signed, 1.0, 0.0, "", -1, false)
                val raw = DoubleArray(sorted.size) { k -> plain.physical(plain.raw(snapshots!!.data[m], k * snapshots.length[m])) }
                fun fits(scale: Pair<Double, Double>) = raw.indices.all { abs(raw[it] * scale.first + scale.second - wanted[it]) <= tolerance }
                val tidied = Analysis.tidy(found.factor, found.offset)
                val tidyFits = fits(tidied)
                if (clockLike && !(tidyFits && Analysis.roundFactor(found.factor) != null && tidied.second == Math.rint(tidied.second))) return@mapNotNull null
                val (factor, offset) = if (tidyFits) tidied else found.factor to found.offset
                Suggestion(match.message, Signal("READ_${name.uppercase().replace(Regex("\\W+"), "_")}_${found.start}", found.start, found.size, found.littleEndian, found.signed, factor, offset, "", -1, false),
                    "Reads your ${points.size} readings of \"$name\" (${wanted.min()} to ${wanted.max()})" +
                        (if (clockLike) " · they only rise or fall, in step with the time, which any trending field fits: kept for its round scale" else ""),
                    if (clockLike) 0.6 else 0.8)
            }
        }

    /** True when [points] (in time order) only rise or only fall, and a straight line in time fits them within [tolerance]. */
    private fun followsTheClock(points: List<Pair<Int, Double>>, tolerance: Double): Boolean {
        val rising = (1 until points.size).all { points[it].second >= points[it - 1].second }
        val falling = (1 until points.size).all { points[it].second <= points[it - 1].second }
        if (!rising && !falling) return false
        val meanT = points.sumOf { it.first.toDouble() } / points.size
        val meanV = points.sumOf { it.second } / points.size
        var tt = 0.0
        var tv = 0.0
        for ((t, v) in points) { tt += (t - meanT) * (t - meanT); tv += (t - meanT) * (v - meanV) }
        if (tt < 1e-12) return false
        val slope = tv / tt
        return points.all { (t, v) -> abs(meanV + slope * (t - meanT) - v) <= tolerance }
    }

    /**
     * Why [signal] can't be what frames [p] carry, or null: a flag or small state that changes in a quarter of the
     * frames is part of a counter or checksum, and a value that always reads its highest is one the car doesn't send.
     */
    private fun misfit(signal: Signal, p: FrameStore.Payloads): String? {
        val name = signal.name.lowercase()
        if (p.size < 20 || signal.muxValue >= 0 || signal.isMultiplexor || "count" in name || "checksum" in name || "crc" in name) return null
        val ones = if (signal.size >= 64) -1L else (1L shl signal.size) - 1
        var changes = 0
        var top = 0
        var previous = 0L
        for (k in 0 until p.size) {
            val value = signal.raw(p.data, k * p.length) and ones
            if (k > 0 && value != previous) changes++
            if (value == ones) top++
            previous = value
        }
        return when {
            signal.size <= 3 && changes * 4 >= p.size -> "flips in ${changes * 100L / p.size}% of the frames"
            signal.size >= 4 && top == p.size -> "always reads its highest value (not available)"
            else -> null
        }
    }

    private const val GRID = 400
    /** A pause longer than this (ticks, 30 s) separates stretches of data: two logs, or a drive and a charge. */
    private const val GAP = 300_000
    private const val MATCH = 0.95
    /** The confidence of a state that follows a measurement: enough to be listed with the named ones. */
    private const val NAMED_STATE = 0.7
    private val PIPE = Regex("isotp|pipe", RegexOption.IGNORE_CASE)
    /** How closely the moments of change must agree too, and how many changes a signal needs to be compared at all. */
    private const val MATCH_CHANGES = 0.8f
    private const val MIN_CHANGES = 5
    /** Frames a message needs before a page selector is looked for in it. */
    private const val MIN_PAGED = 64
    /** Frames a page selector is looked for in at most, in stretches of this many consecutive ones. */
    private const val MUX_FRAMES = 20_000
    private const val MUX_STRETCH = 1000

    /**
     * [known] gives the DBC entry of a message, if any; its signals are left alone and used as references.
     * [markers] are store times with their notes. [progress] is told what is being worked on.
     */
    fun run(store: FrameStore, known: (FrameStore.Message) -> DbcMessage?, markers: List<Pair<Int, String>>, progress: (String) -> Unit = {}): List<Suggestion> {
        val messages = store.messages
        val result = ArrayList<Suggestion>()
        // flags and states that changed up to three times, with the times: what [events] groups
        val rare = HashMap<Suggestion, IntArray>()
        progress("Working out the layout of ${messages.size} messages")
        // an id's messages on all buses are loaded together, as they are compared; a long log a part at a time, as all
        // its frames at once would not fit; the results are kept in the order of the messages
        val layouts = arrayOfNulls<List<Suggestion>>(messages.size)
        for (batch in Analysis.batches(store, messages.groupBy { it.id }.values.toList())) {
            val loaded = Analysis.payloads(store, batch.flatten())
            for (group in batch) {
                for (message in group) layouts[message.index] = decode(message, group, loaded, known, rare)
                for (message in group) loaded.remove(message.index)
            }
        }
        for (found in layouts) if (found != null) result += found
        // markers first: they cut their bits out of the guessed fields, and what is left is then compared
        if (markers.isNotEmpty()) progress("Looking at what changed at ${markers.size} markers")
        // repeats of an action, and its two halves ("door open", "door closed"), are one test: the bit must change at every one
        if (markers.isNotEmpty()) {
            // every marker's window, 1 s before to 2 s after it, counted in one pass
            val second = FrameStore.TICKS_PER_SECOND.toInt()
            val starts = markers.map { it.first - second }.distinct().sorted().toIntArray()
            val (total, windows) = store.windowFlips(starts, 3 * second)
            for ((action, times) in markers.groupBy({ it.second.replace(HALF, "").trim().ifEmpty { it.second }.lowercase() }, { it.first }))
                changedOnlyAt(store, known, total, windows, times.map { starts.binarySearch(it - second) }.distinct(), action, result)
        }
        progress("Comparing ${result.size} fields with the decoded signals")
        nameAfterKnownSignals(store, known, result)
        events(store, result, rare)
        return result.sortedWith(compareByDescending<Suggestion> { it.confidence }.thenBy { it.message.bus }.thenBy { it.message.id }.thenBy { it.signal.start })
    }

    /** The layout of [message], whose frames and those of the rest of its [group] (the same id on other buses) are [loaded]. */
    private fun decode(message: FrameStore.Message, group: List<FrameStore.Message>, loaded: Map<Int, FrameStore.Payloads>, known: (FrameStore.Message) -> DbcMessage?, rare: MutableMap<Suggestion, IntArray>): List<Suggestion> {
        val result = ArrayList<Suggestion>()
        val payloads = loaded.getValue(message.index)
        if (payloads.size < 4) return result
        // the same id on a lower bus sending the same frames: a copy (forwarded, or a comma's echo of what it sent)
        if (group.any { it.bus < message.bus && copies(payloads, loaded.getValue(it.index)) }) return result
        if (diagnostic(message, payloads)) return result
        val free = BooleanArray(payloads.length * 8) { true }
        val dbcMessage = known(message)
        // a pipe carries conversations (ISO-TP over a private id): its bytes are content, not a layout
        if (dbcMessage != null && PIPE.containsMatchIn(dbcMessage.name)) return result
        dbcMessage?.signals?.forEach { signal -> for (bit in signal.bits) if (bit in free.indices) free[bit] = false }
        checksums(message, payloads, free, result)
        layout(message, payloads, free, dbcMessage?.multiplexor?.takeIf { it.fits(payloads.length) }, result, rare)
        // a message has one counter: with one in the DBC, a second is page data that repeats in step with it
        if (dbcMessage != null && dbcMessage.signals.any { "counter" in it.name.lowercase() })
            for (i in result.size - 1 downTo 0) if (result[i].signal.name.startsWith("COUNTER_")) result.removeAt(i)
        // a known message's counter and checksum are called after it, as its maker does (DI_torqueCounter)
        if (dbcMessage != null) for (i in result.indices) {
            val found = result[i]
            val kind = when { found.signal.name.startsWith("COUNTER_") -> "Counter"; found.signal.name.startsWith("CHECKSUM_") -> "Checksum"; else -> continue }
            val name = (dbcMessage.name + kind).let { name -> if (dbcMessage.signals.none { it.name == name } && (0 until i).none { result[it].signal.name == name }) name else "${name}_${found.signal.start}" }
            result[i] = Suggestion(message, field(name, found.signal.start, found.signal.size), found.evidence, found.confidence)
        }
        return result
    }

    /**
     * Diagnostic traffic (ISO-TP: OBD and UDS requests and answers) on the ids set aside for it: its first byte is a
     * frame type and length, not a field, and its contents are a conversation, not a signal.
     */
    private fun diagnostic(message: FrameStore.Message, p: FrameStore.Payloads): Boolean {
        val id = message.id
        if (id !in 0x700..0x7FF && id ushr 16 != 0x18DA && id ushr 16 != 0x18DB) return false
        // each frame must be one ISO-TP could send: single (a length that fits), first (a length past one frame),
        // consecutive, or flow control (status 0-2); a field that merely starts low is none of these
        var typed = 0
        for (k in 0 until p.size) {
            val at = k * p.length
            val first = p.data[at].toInt() and 0xFF
            val low = first and 0x0F
            val valid = when (first shr 4) {
                0 -> low in 1 until p.length
                1 -> p.length > 1 && (low shl 8 or (p.data[at + 1].toInt() and 0xFF)) >= p.length
                2 -> true
                3 -> low <= 2
                else -> false
            }
            if (valid) typed++
        }
        return typed * 20 >= p.size * 19
    }

    /** True when nearly every one of the first frames of [mine] is also a frame of [other]. */
    private fun copies(mine: FrameStore.Payloads, other: FrameStore.Payloads): Boolean {
        if (mine.length != other.length) return false
        // the few frames of [mine] are hashed, with how often each occurs, and [other] is read once against them
        val n = minOf(mine.size, 200)
        val wanted = HashMap<java.nio.ByteBuffer, Int>(n * 2)
        for (k in 0 until n) wanted.merge(java.nio.ByteBuffer.wrap(mine.data, k * mine.length, mine.length).slice(), 1, Int::plus)
        var found = 0
        for (k in 0 until other.size) {
            if (wanted.isEmpty() || found * 10 >= n * 9) break
            found += wanted.remove(java.nio.ByteBuffer.wrap(other.data, k * other.length, other.length).slice()) ?: 0
        }
        return found * 10 >= n * 9
    }

    private fun field(name: String, start: Int, size: Int, signed: Boolean = false, mux: Int = -1, multiplexor: Boolean = false) =
        Signal(name, start, size, true, signed, 1.0, 0.0, "", mux, multiplexor)

    /** A byte that equals a checksum of the rest of the frame, in every frame, becomes a signal and is no longer free. */
    private fun checksums(message: FrameStore.Message, p: FrameStore.Payloads, free: BooleanArray, out: MutableList<Suggestion>) {
        if (p.length < 2 || p.size < 8) return
        val idSum = (message.id and 0xFF) + (message.id ushr 8 and 0xFF)
        // each frame's sum and XOR of all its bytes, once: those of all bytes but one are these less that byte
        val sums = IntArray(p.size)
        val xors = IntArray(p.size)
        for (k in 0 until p.size) {
            var sum = 0
            var x = 0
            for (b in 0 until p.length) { val v = p.data[k * p.length + b].toInt() and 0xFF; sum += v; x = x xor v }
            sums[k] = sum
            xors[k] = x
        }
        val needed = p.size - p.size / 100
        val allowed = p.size - needed
        for (at in 0 until p.length) {
            if (!(at * 8 until at * 8 + 8).all { free[it] }) continue
            // a constant byte can match by accident: all the other bytes constant too
            val first = p.data[at].toInt() and 0xFF
            if ((1 until p.size).none { k -> p.data[k * p.length + at].toInt() and 0xFF != first }) continue
            // a byte that is another byte repeated ([x, x], or x beside zeros) is that byte's sum and XOR too
            if ((0 until p.length).any { o -> o != at && (0 until p.size).all { k -> p.data[k * p.length + at] == p.data[k * p.length + o] } }) continue
            var teslaMisses = 0; var plainMisses = 0; var xorMisses = 0
            for (k in 0 until p.size) {
                val actual = p.data[k * p.length + at].toInt() and 0xFF
                val sum = sums[k] - actual
                if (actual != (sum + idSum) and 0xFF) teslaMisses++
                if (actual != sum and 0xFF) plainMisses++
                if (actual != xors[k] xor actual) xorMisses++
                // every test already failed: the rest of the frames can't change that
                if (teslaMisses > allowed && plainMisses > allowed && xorMisses > allowed) break
            }
            val how = when {
                teslaMisses <= allowed -> "the sum of the other bytes and the ID (Tesla's)"
                plainMisses <= allowed -> "the sum of the other bytes"
                xorMisses <= allowed -> "the XOR of the other bytes"
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
    private fun layout(message: FrameStore.Message, p: FrameStore.Payloads, free: BooleanArray, known: Signal?, out: MutableList<Suggestion>, rare: MutableMap<Suggestion, IntArray>) {
        val all = flips(p, null)
        val selector = known ?: multiplexor(p, free, all)
        if (selector == null) { fields(message, p, null, free, all, -1, out, rare); return }
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
        fields(message, p, null, shared, all, -1, out, rare)
        val own = BooleanArray(all.size) { free[it] && !shared[it] }
        for ((value, rows) in groups.entries.sortedBy { it.key }) fields(message, p, rows.toIntArray(), own, perGroup[value]!!, value.toInt(), out, rare)
    }

    /**
     * A field of up to 8 bits with 2..16 values that changes often, where the other bits flip less than half as much
     * between frames of the same value as between consecutive frames. A plain counter fails that test: frames a whole cycle apart
     * differ at least as much as neighbours.
     */
    private fun multiplexor(p: FrameStore.Payloads, free: BooleanArray, all: IntArray): Signal? {
        // a handful of frames that differ is not pages taking turns
        if (p.size < MIN_PAGED) return null
        // a long log is looked at in stretches of consecutive frames spread evenly over it
        val stretches = if (p.size <= MUX_FRAMES) 1 else MUX_FRAMES / MUX_STRETCH
        val stretch = if (stretches == 1) p.size else MUX_STRETCH
        val frames = stretches * stretch
        val masks = IntArray(p.length)
        // by value (at most 8 bits): the frame it was last seen in, -1 for none in this stretch; and whether seen at all
        val lastAt = IntArray(256)
        val seen = BooleanArray(256)
        for (candidate in segments(all, free)) {
            if (candidate.size > 8) continue
            for (byte in 0 until p.length) masks[byte] = maskOutside(candidate, byte)
            val ones = (1L shl candidate.size) - 1
            seen.fill(false)
            var distinct = 0
            var changes = 0
            var steps = 0
            var consecutive = 0L
            var sameValue = 0L
            scan@ for (s in 0 until stretches) {
                val from = if (stretches == 1) 0 else ((p.size - stretch).toLong() * s / (stretches - 1)).toInt()
                lastAt.fill(-1)
                var previous = 0L
                for (k in from until from + stretch) {
                    val value = candidate.raw(p.data, k * p.length)
                    if (k > from && value != previous) changes++
                    if (k > from && (value - previous) and ones == 1L) steps++
                    previous = value
                    val v = value.toInt()
                    val earlier = lastAt[v]
                    lastAt[v] = k
                    if (!seen[v]) { seen[v] = true; if (++distinct > 16) break@scan }
                    val row = k * p.length
                    for (byte in 0 until p.length) {
                        val now = p.data[row + byte].toInt()
                        if (k > from) consecutive += Integer.bitCount((now xor p.data[row - p.length + byte].toInt()) and masks[byte])
                        if (earlier >= 0) sameValue += Integer.bitCount((now xor p.data[earlier * p.length + byte].toInt()) and masks[byte])
                    }
                }
            }
            // a counter also changes in every frame and repeats its values, but it counts up by one; a selector doesn't
            if (steps >= (frames - stretches) * 0.9) continue
            if (distinct in 2..16 && changes >= frames * 0.3 && consecutive >= frames / 2 && sameValue * 2 < consecutive) return candidate
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
            // most signals end where a byte does: across a byte's edge it takes less of a jump in activity to start a new field
            while (bit + 1 < flips.size && allowed[bit + 1] && flips[bit + 1] > 0 && flips[bit + 1] <= flips[bit] * (if (bit % 8 == 7) 1.5 else 2.5) + 2 && bit + 1 - start < 32) bit++
            result += field("", start, bit - start + 1)
            bit++
        }
        return result
    }

    private fun fields(message: FrameStore.Message, p: FrameStore.Payloads, rows: IntArray?, allowed: BooleanArray, flips: IntArray, mux: Int, out: MutableList<Suggestion>, rare: MutableMap<Suggestion, IntArray>) {
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
            // how it moves: its changes (the first few with their times), falls, summed step, and its time at the bottom and at all ones
            var changes = 0
            val changedAt = IntArray(3)
            var falls = 0
            var stepSum = 0.0
            var lowest = Long.MAX_VALUE
            var atLowest = 0
            var atOnes = 0
            var belowOnes = 0L
            for (i in 0 until n) {
                val row = rows?.get(i) ?: i
                val value = segment.raw(p.data, row * p.length)
                if (distinct.size <= 16) distinct += value
                if (value > highest) highest = value
                if (value < lowest) { lowest = value; atLowest = 0 }
                if (value == lowest) atLowest++
                if (value == half * 2 - 1) atOnes++ else if (value > belowOnes) belowOnes = value
                if (i > 0) {
                    if ((value - previous) and (half * 2 - 1) == 1L) steps++
                    else if (value == 0L) wrapsFrom[previous] = (wrapsFrom[previous] ?: 0) + 1
                    if (abs(value - previous) > half) wraps++
                    // the same two values read as signed
                    val signedStep = abs((if (value >= half) value - 2 * half else value) - (if (previous >= half) previous - 2 * half else previous))
                    if (signedStep > half) signedWraps++
                    if (value != previous) {
                        if (changes < changedAt.size) changedAt[changes] = p.times[row]
                        changes++
                        if (value < previous) falls++
                        stepSum += minOf(abs(value - previous), signedStep)
                    }
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
                    // all ones now and then, far above every other reading: the maker's "not available" (read as signed it would be a lone -1)
                    val sna = size >= 8 && atOnes in 1 until n && belowOnes < (half * 2 - 1) / 10 * 9
                    // jumps across the middle of the range that disappear when read as signed: a value crossing zero
                    val signed = !sna && size >= 4 && wraps > 0 && signedWraps == 0
                    val step = if (changes == 0) 0.0 else stepSum / changes / ((if (sna) belowOnes else highest) - lowest).coerceAtLeast(1)
                    // what its movement looks like, as a hint for what to compare it with
                    val shape = when {
                        changes >= MIN_CHANGES && falls * 50 <= changes -> "only ever rises: a clock, a distance or an energy total"
                        atLowest * 10 >= n * 6 -> "rests at its lowest value and rises in bursts: pedal-, torque- or current-like"
                        changes * 50 <= n && step <= 0.05 -> "drifts slowly: temperature- or level-like"
                        changes * 10 >= n * 8 && step >= 0.1 -> "jumps in every frame: noise, or not one field"
                        step <= 0.03 -> "moves smoothly: speed- or angle-like"
                        else -> null
                    }
                    Suggestion(message, field("${prefix}FIELD_$start", start, size, signed, mux).also { if (sna) it.valueNames = mapOf(half * 2 - 1 to "SNA") },
                        (if (signed) "Measurement, signed: it crosses zero" else "Measurement: a value that varies") + (shape?.let { " · $it" } ?: "") + (if (sna) " · all ones is \"not available\"" else ""), 0.4)
                }
            }
            if (mux < 0 && size <= 8 && changes in 1..changedAt.size && out.last().confidence < 0.5) rare[out.last()] = changedAt.copyOf(changes)
        }
    }

    /**
     * One decoded or guessed signal at the [GRID] moments, with mean 0 and length 1, so a dot product is a correlation;
     * and the same for its changes from one moment to the next. Two signals that only both drift, or both step once,
     * agree in value but not in when they change.
     */
    private class Vector(val values: FloatArray, val changes: FloatArray, val mean: Double, val spread: Double, /** How many different values it took. */ val distinct: Int)

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
        return Vector(FloatArray(GRID) { ((raw[it] - mean) / norm).toFloat() }, changes, mean, norm, raw.toHashSet().size)
    }

    /** Renames and scales the unnamed fields that follow a decoded signal: the same quantity sent again, often on another bus. */
    private fun nameAfterKnownSignals(store: FrameStore, known: (FrameStore.Message) -> DbcMessage?, suggestions: MutableList<Suggestion>) {
        if (store.lastTime <= store.firstTime) return
        // the stretches with data: several logs opened together, or a drive and a charge, leave gaps the grid skips
        val segments = ArrayList<IntArray>()
        var from = store.firstTime
        var last = from
        for (chunk in store.chunks) for (i in 0 until chunk.size) {
            val t = chunk.time[i]
            if (t - last > GAP) { segments += intArrayOf(from, last); from = t }
            if (t > last) last = t
        }
        segments += intArrayOf(from, last)
        val active = segments.sumOf { (it[1] - it[0]).toLong() }.coerceAtLeast(1)
        val segmentOf = IntArray(GRID)
        val times = IntArray(GRID) { k ->
            var left = active * k / (GRID - 1)
            var s = 0
            while (s < segments.size - 1 && left > segments[s][1] - segments[s][0]) { left -= segments[s][1] - segments[s][0]; s++ }
            segmentOf[k] = s
            (segments[s][0] + left).toInt().coerceAtMost(segments[s][1])
        }
        val snapshots = store.snapshots(times)
        val step = active.toDouble() / (GRID - 1) / FrameStore.TICKS_PER_SECOND
        // [how] it is derived from the signal: as it is, its rate of change (acceleration from speed) or its running total (distance from speed)
        class Reference(val message: FrameStore.Message, val signal: Signal, val vector: Vector, val how: String, val unit: String, val raw: DoubleArray)
        val references = ArrayList<Reference>()
        for (m in snapshots.messages.indices) {
            for (signal in known(snapshots.messages[m])?.signals.orEmpty()) {
                val name = signal.name.lowercase()
                // a multiplexor only says which page a frame carries: anything else that pages through in step matches it
                if (signal.muxValue >= 0 || signal.isMultiplexor || "checksum" in name || "counter" in name || "crc" in name) continue
                val raw = values(snapshots, m, signal) ?: continue
                val message = snapshots.messages[m]
                vector(raw)?.let { references += Reference(message, signal, it, "", signal.unit, raw) }
                val rate = DoubleArray(GRID) { k -> (raw[minOf(k + 1, GRID - 1)] - raw[minOf(k + 1, GRID - 1) - 1]) / step }
                vector(rate)?.let { references += Reference(message, signal, it, "RATE_", signal.unit + "/s", rate) }
                var sum = 0.0
                val total = DoubleArray(GRID) { k -> sum += raw[k] * step; sum }
                vector(total)?.let { references += Reference(message, signal, it, "TOTAL_", signal.unit + "·s", total) }
            }
        }
        // slow modules answer late: a match may lag by up to 2 s, tried only where the plain match is already promising
        // (none when the grid's moments are more than 2 s apart: a shift of one would be a longer lag than that)
        val lags = (2.0 / step).toInt().coerceIn(0, 20)
        val vectors = HashMap<Int, Vector?>()
        if (references.isNotEmpty()) for (i in suggestions.indices) {
            val suggestion = suggestions[i]
            val field = suggestion.signal
            if (suggestion.confidence > 0.5 || field.muxValue >= 0 || suggestion.message.index >= snapshots.messages.size) continue
            val mine = vectors.getOrPut(i) { vector(snapshots, suggestion.message.index, field) } ?: continue
            var best: Reference? = null
            var bestR = 0f
            var bestLag = 0
            for (reference in references) {
                // its own message, or that id on another bus: a field that repeats a signal of the same frame says nothing new;
                // a single bit can't stand for a signal of several bits
                if (reference.message.id == suggestion.message.id || (field.size == 1 && reference.signal.size > 1)) continue
                var r = dot(mine.values, reference.vector.values)
                var lag = 0
                if (abs(r) >= 0.6) for (s in -lags..lags) { val shifted = shifted(mine.values, reference.vector.values, s); if (abs(shifted) > abs(r)) { r = shifted; lag = s } }
                // a rate is already a change, and its own changes are mostly rounding noise: it is held to the value test alone
                if (abs(r) <= abs(bestR) || (reference.how != "RATE_" && shifted(mine.changes, reference.vector.changes, lag) * Math.signum(r) < MATCH_CHANGES)) continue
                bestR = r; best = reference; bestLag = lag
            }
            if (best == null || abs(bestR) < MATCH) continue
            val where = "(bus ${best.message.bus}, ${"%X".format(best.message.id)})"
            // the very same number at nearly every sampled moment: the signal itself, sent again (read signed when it is)
            val asReference = Signal(field.name, field.start, field.size, true, best.signal.signed, 1.0, 0.0, "", -1, false)
            val same = if (best.how != "") 0 else values(snapshots, suggestion.message.index, asReference)!!.let { mine -> (0 until GRID).count { best.signal.physical(mine[it].toLong()) == best.raw[it] } }
            if (same * 100 >= GRID * 99) {
                val copy = Signal("COPY_${best.signal.name}_${field.start}", field.start, field.size, true, best.signal.signed, best.signal.factor, best.signal.offset, best.signal.unit, -1, false)
                copy.valueNames = best.signal.valueNames
                suggestions[i] = Suggestion(suggestion.message, copy, "Copy of ${best.signal.name} $where: the same value at $same of $GRID sampled moments", 0.97)
                continue
            }
            // a few values that change when a measurement of many does: a state that follows it (awake when the voltage is up), not that measurement
            if (best.how == "" && mine.distinct <= 8 && best.vector.distinct > mine.distinct * 3) {
                suggestions[i] = Suggestion(suggestion.message, field("STATE_${field.start}", field.start, field.size),
                    "State: takes ${mine.distinct} values, changing when ${best.signal.name} $where does, r = ${"%.3f".format(abs(bestR))}", NAMED_STATE)
                continue
            }
            // the same match in each stretch of data (each log, when several are open): less sure where one disagrees
            val tested = segments.indices.mapNotNull { s -> segmentR(mine.values, best.vector.values, segmentOf, s, bestLag) }
            val agree = tested.count { it * Math.signum(bestR) >= MATCH - 0.05 }
            // a flag or a state is the same values as the signal it follows, not a scaled copy: kept raw, with its names when they match
            val state = best.how == "" && (field.size <= 4 || best.signal.size <= 4 || best.signal.valueNames.isNotEmpty())
            val fitted = bestR * best.vector.spread / mine.spread
            val (factor, offset) = if (state) 1.0 to 0.0 else {
                // the rounded scale is checked against the values it was fitted to, over the moments the lag leaves
                val raw = values(snapshots, suggestion.message.index, field)!!
                val overlap = maxOf(0, -bestLag) until minOf(GRID, GRID - bestLag)
                val x = DoubleArray(overlap.count()) { raw[overlap.first + it] }
                val y = DoubleArray(x.size) { best.raw[overlap.first + it + bestLag] }
                Analysis.tidyChecked(fitted, best.vector.mean - fitted * mine.mean, x, y)
            }
            val signal = Signal("LIKE_${best.how}${best.signal.name}_${field.start}", field.start, field.size, true, field.signed, factor, offset, if (state) "" else best.unit, -1, false)
            if (state && bestR > 0 && abs(best.signal.factor - 1.0) < 1e-9 && best.signal.offset == 0.0) signal.valueNames = best.signal.valueNames
            val what = when (best.how) { "RATE_" -> "the rate of change of "; "TOTAL_" -> "the running total of "; else -> "" }
            val late = if (bestLag == 0) "" else " about %.1f s %s".format(abs(bestLag) * step, if (bestLag > 0) "earlier" else "later")
            val logs = if (tested.size >= 2) ", in $agree of ${tested.size} stretches of data" else ""
            suggestions[i] = Suggestion(suggestion.message, signal,
                "Moves with $what${best.signal.name}$late $where, r = ${"%.3f".format(abs(bestR))}$logs" + if (state) "" else "; scaled to its units",
                abs(bestR).toDouble() * if (agree < tested.size) 0.9 else 1.0)
        }
        // unknown fields that move together are likely one quantity several times (four wheels) or one system: grouped, so a name or a test covers all
        val open = suggestions.indices.filter { suggestions[it].confidence < 0.5 && suggestions[it].signal.size >= 4 && suggestions[it].signal.muxValue < 0 }
        val group = IntArray(suggestions.size) { it }
        fun root(i: Int): Int { var r = i; while (group[r] != r) r = group[r]; return r }
        for ((x, a) in open.withIndex()) {
            val va = vectors.getOrPut(a) { vector(snapshots, suggestions[a].message.index, suggestions[a].signal) } ?: continue
            for (b in open.drop(x + 1)) {
                val vb = vectors.getOrPut(b) { vector(snapshots, suggestions[b].message.index, suggestions[b].signal) } ?: continue
                val r = dot(va.values, vb.values)
                if (abs(r) >= MATCH && dot(va.changes, vb.changes) * Math.signum(r) >= MATCH_CHANGES) group[root(b)] = root(a)
            }
        }
        val groups = open.groupBy { root(it) }.values.filter { it.size >= 2 }
        for ((n, members) in groups.withIndex()) for (i in members) {
            val old = suggestions[i]
            val others = members.filter { it != i }.joinToString { "%X bit %d".format(suggestions[it].message.id, suggestions[it].signal.start) }
            suggestions[i] = Suggestion(old.message, old.signal.renamed("G${n + 1}_${old.signal.name}"),
                "${old.evidence} · group ${n + 1}: moves with $others", 0.45)
        }
    }

    /**
     * Fields that changed only a few times and first did so within a second of each other: one thing that happened
     * (a plug going in, the car waking). They are numbered E1, E2 ... and carry the moment, to look at and name together.
     */
    private fun events(store: FrameStore, suggestions: MutableList<Suggestion>, rare: Map<Suggestion, IntArray>) {
        val moments = suggestions.indices.mapNotNull { i -> rare[suggestions[i]]?.let { i to it[0] } }.sortedBy { it.second }
        var number = 0
        var from = 0
        while (from < moments.size) {
            var to = from
            // within a second of the group's first: compared with the last, changes a second apart each would chain up into one event
            while (to + 1 < moments.size && moments[to + 1].second - moments[from].second <= FrameStore.TICKS_PER_SECOND) to++
            if (to > from) {
                number++
                val at = moments[from].second
                val note = " · event $number at %.1f s: changed together with ".format((at - store.firstTime) / FrameStore.TICKS_PER_SECOND.toDouble()) + if (to - from == 1) "one other field" else "${to - from} other fields"
                for (k in from..to) {
                    val old = suggestions[moments[k].first]
                    suggestions[moments[k].first] = Suggestion(old.message, old.signal.renamed("E${number}_${old.signal.name}"), old.evidence + note, 0.45, at = at)
                }
            }
            from = to + 1
        }
    }

    /** Correlation of [a] and [b] (normalized as in [Vector]) with [b] [shift] places ahead, over the overlap. */
    private fun shifted(a: FloatArray, b: FloatArray, shift: Int): Float {
        var r = 0f
        for (k in maxOf(0, -shift) until minOf(a.size, b.size - shift)) r += a[k] * b[k + shift]
        return r
    }

    /** Pearson correlation of [a] and [b] (b [shift] places ahead) over the grid points of stretch [segment]; null when one doesn't move there. */
    private fun segmentR(a: FloatArray, b: FloatArray, segmentOf: IntArray, segment: Int, shift: Int): Double? {
        val ks = (maxOf(0, -shift) until minOf(a.size, b.size - shift)).filter { segmentOf[it] == segment }
        if (ks.size < 20) return null
        val ma = ks.sumOf { a[it].toDouble() } / ks.size
        val mb = ks.sumOf { b[it + shift].toDouble() } / ks.size
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (k in ks) { val da = a[k] - ma; val db = b[k + shift] - mb; sab += da * db; saa += da * da; sbb += db * db }
        return if (saa < 1e-12 || sbb < 1e-12) null else sab / Math.sqrt(saa * sbb)
    }

    /** The last word of a marker note that says which half of an action it was. */
    private val HALF = Regex("\\s+(on|off|in|out|open|opened|close|closed|up|down|start|stop|pressed|released|lock|locked|unlock|unlocked)$", RegexOption.IGNORE_CASE)

    /** Bits that flipped in every one of windows [mine] (indexes into [windows]) and at no other time. */
    private fun changedOnlyAt(store: FrameStore, known: (FrameStore.Message) -> DbcMessage?, total: Array<IntArray?>, windows: Array<Array<IntArray?>?>, mine: List<Int>, note: String, out: MutableList<Suggestion>) {
        val name = "EVT_" + note.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_').take(24).ifEmpty { "MARKER" }
        for (m in total.indices) {
            val flips = total[m] ?: continue
            val inWindows = windows[m] ?: continue
            val message = store.messages[m]
            val taken = BooleanArray(flips.size)
            known(message)?.signals?.forEach { signal -> for (bit in signal.bits) if (bit in taken.indices) taken[bit] = true }
            // flipped in each of its windows, and all of its flips are in them
            fun only(bit: Int) = !taken[bit] && mine.all { (inWindows[it]?.get(bit) ?: 0) > 0 } && flips[bit] == mine.sumOf { inWindows[it]?.get(bit) ?: 0 }
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
                    // little-endian: the sign bit is the top one, so only the upper piece can be signed
                    if (guess.start < start) out += Suggestion(message, field(guess.name, guess.start, start - guess.start, mux = guess.muxValue), old.evidence, old.confidence)
                    if (end > bit) out += Suggestion(message, field(guess.name.substringBeforeLast('_') + "_" + bit, bit, end - bit, guess.signed, guess.muxValue), old.evidence, old.confidence)
                }
                out += Suggestion(message, field("${name}_$start", start, bit - start),
                    if (mine.size == 1) "Changed only at the marker \"$note\"" else "Changed at all ${mine.size} \"$note\" markers and at no other time",
                    if (mine.size == 1) 0.8 else 0.95)
            }
        }
    }
}
