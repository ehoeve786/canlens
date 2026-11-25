package com.ehoeve.canlens.core

/**
 * Receives CAN frames. `data[offset until offset + length]` is only valid during the call.
 * Returns false to make the producer stop.
 */
fun interface FrameSink {
    fun frame(timeMicros: Long, bus: Int, id: Int, data: ByteArray, offset: Int, length: Int): Boolean
}

/**
 * All frames of the open logs or the live stream, in arrival order, in fixed-size chunks of primitive arrays
 * (about 19 bytes a frame, no object per frame). One writer thread, any number of readers: a reader takes
 * [chunks] and each chunk's [Chunk.size] once and may read everything below them without locking.
 */
class FrameStore(private val maxChunks: Int = 40) : FrameSink {

    class Chunk {
        /** Time since [originMicros] in units of 100 µs. */
        @JvmField val time = IntArray(CAPACITY)
        /** Index into [messages], as an unsigned short. */
        @JvmField val message = ShortArray(CAPACITY)
        /** End of each frame's payload in [blob]; it starts where the previous one ends. */
        @JvmField val end = IntArray(CAPACITY)
        @JvmField val blob = ByteArray(CAPACITY * 8)
        /** Bit b: byte b differs from the message's previous frame; bit 7 stands for byte 7 and all after it. 0 for a message's first frame. */
        @JvmField val changed = ByteArray(CAPACITY)
        @Volatile @JvmField var size = 0

        fun start(index: Int) = if (index == 0) 0 else end[index - 1]
    }

    class Message(val bus: Int, val id: Int, val index: Int, @JvmField val firstTime: Int) {
        @Volatile @JvmField var count = 0L
        @Volatile @JvmField var lastTime = firstTime
        /** Payload of the latest frame; replaced, never modified. */
        @Volatile @JvmField var data = ByteArray(0)
        /** Which bytes of [data] differ from the frame before it, as in [Chunk.changed]. */
        @Volatile @JvmField var changed = 0

        /** Average frames per second over the time this message was seen. */
        val rate: Double get() = if (lastTime > firstTime) (count - 1) * TICKS_PER_SECOND / (lastTime - firstTime) else 0.0
    }

    @Volatile var chunks: Array<Chunk> = emptyArray(); private set
    @Volatile var messages: Array<Message> = emptyArray(); private set
    /** Timestamp of time 0, in the source's own clock. */
    @Volatile var originMicros = Long.MIN_VALUE; private set
    @Volatile var lastTime = 0; private set
    @Volatile var frameCount = 0L; private set
    /** Set when a log did not fit: the frames after the limit were not loaded. */
    @Volatile var truncated = false; private set
    /** Live streams drop their oldest chunk when full; logs stop loading instead. */
    @Volatile var dropOldest = false

    private val byKey = HashMap<Long, Message>()

    @Synchronized
    override fun frame(timeMicros: Long, bus: Int, id: Int, data: ByteArray, offset: Int, length: Int): Boolean {
        if (truncated || length < 0 || length > MAX_PAYLOAD) return !truncated
        if (originMicros == Long.MIN_VALUE) originMicros = timeMicros
        val time = ((timeMicros - originMicros) / MICROS_PER_TICK).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        val key = bus.toLong() shl 32 or (id.toLong() and 0xFFFFFFFFL)
        val message = byKey[key] ?: run {
            if (messages.size >= 65536) return true
            Message(bus, id, messages.size, time).also { byKey[key] = it; messages += it }
        }
        var chunk = chunks.lastOrNull()
        if (chunk == null || chunk.size == CAPACITY || chunk.start(chunk.size) + length > chunk.blob.size) {
            if (chunks.size >= maxChunks) {
                if (!dropOldest) { truncated = true; return false }
                chunks = chunks.copyOfRange(1, chunks.size)
            }
            chunk = Chunk()
            chunks += chunk
        }
        val index = chunk.size
        val start = chunk.start(index)
        System.arraycopy(data, offset, chunk.blob, start, length)
        chunk.time[index] = time
        chunk.message[index] = message.index.toShort()
        chunk.end[index] = start + length
        val previous = message.data
        var changed = 0
        if (message.count > 0) {
            for (b in 0 until length) if (b >= previous.size || data[offset + b] != previous[b]) changed = changed or (1 shl minOf(b, 7))
        }
        chunk.changed[index] = changed.toByte()
        chunk.size = index + 1
        message.data = data.copyOfRange(offset, offset + length)
        message.changed = changed
        message.lastTime = time
        message.count++
        lastTime = time
        frameCount++
        return true
    }

    /**
     * Puts the frames of [other] on this store's timeline, in time order with its own: [shiftMicros] is added to their
     * time stamps and [busOffset] to their bus numbers, which keeps the two recordings apart. Time 0 moves when
     * [other] starts earlier; the return value is how many ticks to add to times taken before the merge.
     */
    @Synchronized
    fun merge(other: FrameStore, shiftMicros: Long, busOffset: Int): Int {
        val merged = FrameStore(maxChunks)
        val oldOrigin = originMicros
        val mine = Cursor(this, 0, 0)
        val theirs = Cursor(other, shiftMicros, busOffset)
        while (mine.micros != Long.MAX_VALUE || theirs.micros != Long.MAX_VALUE) {
            val next = if (mine.micros <= theirs.micros) mine else theirs
            if (!next.moveInto(merged)) break
        }
        chunks = merged.chunks; messages = merged.messages
        byKey.clear(); byKey.putAll(merged.byKey)
        originMicros = merged.originMicros; lastTime = merged.lastTime; frameCount = merged.frameCount; truncated = merged.truncated
        return if (oldOrigin == Long.MIN_VALUE || originMicros == Long.MIN_VALUE) 0 else ((oldOrigin - originMicros) / MICROS_PER_TICK).toInt()
    }

    /** Walks a store's frames in order; [micros] is the current frame's shifted time, Long.MAX_VALUE after the last. */
    private class Cursor(private val store: FrameStore, private val shiftMicros: Long, private val busOffset: Int) {
        private val chunks = store.chunks
        private val known = store.messages
        private var chunk = 0
        private var index = 0
        var micros = Long.MAX_VALUE; private set

        init { settle() }

        private fun settle() {
            while (chunk < chunks.size && index >= chunks[chunk].size) { chunk++; index = 0 }
            micros = if (chunk < chunks.size) store.originMicros + chunks[chunk].time[index] * MICROS_PER_TICK + shiftMicros else Long.MAX_VALUE
        }

        /** Hands the current frame to [sink] and steps to the next; false when the sink is full. */
        fun moveInto(sink: FrameStore): Boolean {
            val from = chunks[chunk]
            val message = known[from.message[index].toInt() and 0xFFFF]
            val start = from.start(index)
            val taken = sink.frame(micros, message.bus + busOffset, message.id, from.blob, start, from.end[index] - start)
            index++
            settle()
            return taken
        }
    }

    /** First time still held (later than 0 once a live stream has dropped chunks). */
    val firstTime: Int get() = chunks.firstOrNull()?.takeIf { it.size > 0 }?.time?.get(0) ?: 0

    @Synchronized
    fun clear() {
        chunks = emptyArray(); messages = emptyArray(); byKey.clear()
        originMicros = Long.MIN_VALUE; lastTime = 0; frameCount = 0; truncated = false
    }

    /**
     * Times (seconds) and values of [signal] in every held frame of [message] between [fromTime] and [toTime].
     * Frames too short for the signal, or carrying another multiplexed group, are left out.
     */
    fun series(message: Message, dbcMessage: DbcMessage, signal: Signal, fromTime: Int = Int.MIN_VALUE, toTime: Int = Int.MAX_VALUE): Series {
        var times = FloatArray(1024)
        var values = FloatArray(1024)
        var n = 0
        val wanted = message.index.toShort()
        val multiplexor = if (signal.muxValue >= 0) dbcMessage.multiplexor else null
        for (chunk in chunks) {
            val size = chunk.size
            if (size == 0 || chunk.time[size - 1] < fromTime) continue
            if (chunk.time[0] > toTime) break
            for (i in 0 until size) {
                if (chunk.message[i] != wanted) continue
                val t = chunk.time[i]
                if (t < fromTime || t > toTime) continue
                val start = chunk.start(i)
                val length = chunk.end[i] - start
                if (!signal.fits(length)) continue
                if (multiplexor != null && (!multiplexor.fits(length) || multiplexor.raw(chunk.blob, start) != signal.muxValue.toLong())) continue
                if (n == times.size) { times = times.copyOf(n * 2); values = values.copyOf(n * 2) }
                times[n] = t / TICKS_PER_SECOND.toFloat()
                values[n] = signal.physical(signal.raw(chunk.blob, start)).toFloat()
                n++
            }
        }
        return Series(times, values, n)
    }

    /** Payload of the last frame of [message] at or before [time], or null. */
    fun frameAt(message: Message, time: Int): ByteArray? {
        val wanted = message.index.toShort()
        val all = chunks
        for (c in all.indices.reversed()) {
            val chunk = all[c]
            val size = chunk.size
            if (size == 0 || chunk.time[0] > time) continue
            for (i in size - 1 downTo 0) {
                if (chunk.message[i] == wanted && chunk.time[i] <= time) return chunk.blob.copyOfRange(chunk.start(i), chunk.end[i])
            }
        }
        return null
    }

    /** How often each payload bit of a message flipped inside a time window, and whether it ever flipped outside it. */
    class BitActivity(@JvmField val message: Message, bits: Int) {
        @JvmField val inside = IntArray(bits)
        @JvmField val outside = IntArray(bits)
        @JvmField var frames = 0
        /** Bits that changed in the window and at no other time: what to look at after pressing a button in the car. */
        val onlyInside: Int get() = inside.indices.count { inside[it] > 0 && outside[it] == 0 }
    }

    /**
     * Bit flips of every message, counted separately for frames in [fromTime]..[toTime] and for the rest, in one pass.
     * Index = byte * 8 + bit. Indexed like [messages]; null for a message that didn't occur.
     */
    fun bitActivity(fromTime: Int = Int.MIN_VALUE, toTime: Int = Int.MAX_VALUE, only: Message? = null): Array<BitActivity?> {
        val known = messages
        val result = arrayOfNulls<BitActivity>(known.size)
        val previous = arrayOfNulls<ByteArray>(known.size)
        val previousLength = IntArray(known.size)
        val wanted = only?.index ?: -1
        for (chunk in chunks) {
            val size = chunk.size
            for (i in 0 until size) {
                val m = chunk.message[i].toInt() and 0xFFFF
                if (m >= known.size || (wanted >= 0 && m != wanted)) continue
                val start = chunk.start(i)
                val length = chunk.end[i] - start
                val last = previous[m] ?: ByteArray(MAX_PAYLOAD).also { previous[m] = it; result[m] = BitActivity(known[m], MAX_PAYLOAD * 8) }
                val activity = result[m]!!
                val t = chunk.time[i]
                val counts = if (t in fromTime..toTime) activity.inside else activity.outside
                if (t in fromTime..toTime) activity.frames++
                for (b in 0 until minOf(length, previousLength[m])) {
                    var changed = (chunk.blob[start + b].toInt() xor last[b].toInt()) and 0xFF
                    while (changed != 0) {
                        val bit = Integer.numberOfTrailingZeros(changed)
                        counts[b * 8 + bit]++
                        changed = changed and (changed - 1)
                    }
                }
                System.arraycopy(chunk.blob, start, last, 0, length)
                previousLength[m] = length
            }
        }
        return result
    }

    /**
     * Bit flips of every message in each of the windows [starts]`[w]`..starts[w] + [span] (ascending; a frame in two
     * overlapping windows counts in the later), and in all frames, in one pass. `[m][w]` is null where message m had
     * no flips in window w. Index = byte * 8 + bit, as in [bitActivity].
     */
    fun windowFlips(starts: IntArray, span: Int): Pair<Array<IntArray?>, Array<Array<IntArray?>?>> {
        val known = messages
        val total = arrayOfNulls<IntArray>(known.size)
        val windows = arrayOfNulls<Array<IntArray?>>(known.size)
        val previous = arrayOfNulls<ByteArray>(known.size)
        val previousLength = IntArray(known.size)
        for (chunk in chunks) {
            for (i in 0 until chunk.size) {
                val m = chunk.message[i].toInt() and 0xFFFF
                if (m >= known.size) continue
                val start = chunk.start(i)
                val length = chunk.end[i] - start
                val last = previous[m] ?: ByteArray(MAX_PAYLOAD).also { previous[m] = it; total[m] = IntArray(MAX_PAYLOAD * 8) }
                val t = chunk.time[i]
                var w = java.util.Arrays.binarySearch(starts, t).let { if (it < 0) -it - 2 else it }
                if (w >= 0 && t > starts[w] + span) w = -1
                for (b in 0 until minOf(length, previousLength[m])) {
                    var changed = (chunk.blob[start + b].toInt() xor last[b].toInt()) and 0xFF
                    while (changed != 0) {
                        val bit = b * 8 + Integer.numberOfTrailingZeros(changed)
                        total[m]!![bit]++
                        if (w >= 0) (windows[m] ?: arrayOfNulls<IntArray>(starts.size).also { windows[m] = it }).let { it[w] ?: IntArray(MAX_PAYLOAD * 8).also { counts -> it[w] = counts } }[bit]++
                        changed = changed and (changed - 1)
                    }
                }
                System.arraycopy(chunk.blob, start, last, 0, length)
                previousLength[m] = length
            }
        }
        return total to windows
    }

    /** Passes every held frame to [sink] in order, until it returns false. */
    fun replay(sink: FrameSink) {
        val known = messages
        for (chunk in chunks) {
            for (i in 0 until chunk.size) {
                val m = known.getOrNull(chunk.message[i].toInt() and 0xFFFF) ?: continue
                val start = chunk.start(i)
                if (!sink.frame(originMicros + chunk.time[i] * MICROS_PER_TICK, m.bus, m.id, chunk.blob, start, chunk.end[i] - start)) return
            }
        }
    }

    /** The payload every message had at each of a set of times: row k of message m is data[m][k * length[m] until (k + 1) * length[m]]. */
    class Snapshots(@JvmField val times: IntArray, @JvmField val messages: Array<Message>) {
        @JvmField val length = IntArray(messages.size) { messages[it].data.size }
        @JvmField val data = Array(messages.size) { ByteArray(times.size * length[it]) }
        /** False where the message had not been seen yet, or its frame had another length. */
        @JvmField val valid = Array(messages.size) { BooleanArray(times.size) }
    }

    /** One pass over the store; [times] must be ascending. */
    fun snapshots(times: IntArray): Snapshots {
        val result = Snapshots(times, messages)
        val count = result.messages.size
        val lastChunk = arrayOfNulls<Chunk>(count)
        val lastIndex = IntArray(count)
        var k = 0
        fun take() {
            for (m in 0 until count) {
                val chunk = lastChunk[m] ?: continue
                val start = chunk.start(lastIndex[m])
                val length = result.length[m]
                if (chunk.end[lastIndex[m]] - start != length) continue
                System.arraycopy(chunk.blob, start, result.data[m], k * length, length)
                result.valid[m][k] = true
            }
            k++
        }
        scan@ for (chunk in chunks) {
            for (i in 0 until chunk.size) {
                val t = chunk.time[i]
                while (k < times.size && times[k] < t) take()
                if (k == times.size) break@scan
                val m = chunk.message[i].toInt() and 0xFFFF
                if (m < count) { lastChunk[m] = chunk; lastIndex[m] = i }
            }
        }
        while (k < times.size) take()
        return result
    }

    /** Every held frame of one message: frame k is data[k * length until (k + 1) * length], shorter frames zero-padded. */
    class Payloads(@JvmField val times: IntArray, @JvmField val data: ByteArray, @JvmField val length: Int, @JvmField val size: Int)

    fun payloads(message: Message): Payloads {
        val length = message.data.size.coerceAtLeast(1)
        var times = IntArray(1024)
        var data = ByteArray(1024 * length)
        var n = 0
        val wanted = message.index.toShort()
        for (chunk in chunks) {
            for (i in 0 until chunk.size) {
                if (chunk.message[i] != wanted) continue
                if (n == times.size) { times = times.copyOf(n * 2); data = data.copyOf(n * 2 * length) }
                val start = chunk.start(i)
                System.arraycopy(chunk.blob, start, data, n * length, minOf(length, chunk.end[i] - start))
                times[n++] = chunk.time[i]
            }
        }
        return Payloads(times, data, length, n)
    }

    /** One row of the fixed view: what a message last sent, and how many frames it had sent by then. */
    class Latest(@JvmField val message: Message, @JvmField val time: Int, @JvmField val data: ByteArray, @JvmField val changed: Int, @JvmField val count: Long)

    /**
     * Every message's last frame at or before [time] (null: the newest, which needs no pass over the frames),
     * left out where [filter] rejects that frame. Ordered like [messages].
     */
    fun latest(time: Int?, filter: Filter? = null): List<Latest> {
        val known = messages
        val result = ArrayList<Latest>(known.size)
        fun add(message: Message, at: Int, data: ByteArray, changed: Int, count: Long) {
            if (filter == null || filter.test(message, at, data, 0, data.size, changed)) result += Latest(message, at, data, changed, count)
        }
        if (time == null) {
            for (message in known) add(message, message.lastTime, message.data, message.changed, message.count)
            return result
        }
        val all = chunks
        val position = IntArray(known.size) { -1 }
        val count = IntArray(known.size)
        scan@ for (c in all.indices) {
            val chunk = all[c]
            for (i in 0 until chunk.size) {
                if (chunk.time[i] > time) break@scan
                val m = chunk.message[i].toInt() and 0xFFFF
                if (m < known.size) { position[m] = c shl CHUNK_BITS or i; count[m]++ }
            }
        }
        for (m in known.indices) {
            if (position[m] < 0) continue
            val chunk = all[position[m] ushr CHUNK_BITS]
            val i = position[m] and CAPACITY - 1
            add(known[m], chunk.time[i], chunk.blob.copyOfRange(chunk.start(i), chunk.end[i]), chunk.changed[i].toInt() and 0xFF, count[m].toLong())
        }
        return result
    }

    /**
     * A frozen selection of held frames, oldest first: all of them, or those a filter let through.
     * [position] packs a row's place as chunk shl [CHUNK_BITS] or index, into [chunks].
     */
    class Rows internal constructor(@JvmField val chunks: Array<Chunk>, private val starts: IntArray?, private val picked: IntArray?, @JvmField val size: Int) {
        fun position(row: Int): Int {
            if (picked != null) return picked[row]
            var c = java.util.Arrays.binarySearch(starts!!, row)
            if (c < 0) c = -c - 2
            return c shl CHUNK_BITS or (row - starts[c])
        }

        fun time(row: Int): Int = position(row).let { chunks[it ushr CHUNK_BITS].time[it and CAPACITY - 1] }

        /** The last row at or before [time]; 0 when there is none. */
        fun rowAt(time: Int): Int {
            var low = 0
            var high = size - 1
            var found = 0
            while (low <= high) {
                val middle = (low + high) ushr 1
                if (time(middle) <= time) { found = middle; low = middle + 1 } else high = middle - 1
            }
            return found
        }
    }

    /**
     * Keeps the rows passing [filter] up to date while frames arrive: an [update] reads only the frames added since
     * the one before, and starts over when the store was cleared or dropped its oldest chunk.
     */
    inner class RowScan(private val filter: Filter?) {
        private var scanned = emptyArray<Chunk>()
        private var picked = IntArray(0)
        private var count = 0
        private var doneChunk = 0
        private var doneIndex = 0

        @Synchronized
        fun update(): Rows {
            val all = chunks
            val sizes = IntArray(all.size) { all[it].size }
            if (filter == null) {
                val starts = IntArray(all.size)
                var total = 0
                for (c in all.indices) { starts[c] = total; total += sizes[c] }
                return Rows(all, starts, null, total)
            }
            // a fresh array, as rows handed out earlier still read the old one
            if (all.isEmpty() || scanned.isEmpty() || scanned[0] !== all[0]) { picked = IntArray(1024); count = 0; doneChunk = 0; doneIndex = 0 }
            scanned = all
            val known = messages
            for (c in doneChunk until all.size) {
                val chunk = all[c]
                for (i in (if (c == doneChunk) doneIndex else 0) until sizes[c]) {
                    val m = chunk.message[i].toInt() and 0xFFFF
                    val start = chunk.start(i)
                    if (m >= known.size || !filter.test(known[m], chunk.time[i], chunk.blob, start, chunk.end[i] - start, chunk.changed[i].toInt() and 0xFF)) continue
                    if (count == picked.size) picked = picked.copyOf(count * 2)
                    picked[count++] = c shl CHUNK_BITS or i
                }
            }
            if (all.isNotEmpty()) { doneChunk = all.size - 1; doneIndex = sizes[doneChunk] }
            return Rows(all, null, picked, count)
        }
    }

    /**
     * Why [filter] lets no held frame through, in words for the user: the first term that no frame meets, or the first
     * that no frame meets together with the terms before it. Null when frames do pass, or it is a single term.
     */
    fun whyEmpty(filter: Filter): String? {
        val terms = filter.terms
        if (terms.size < 2) return null
        val alone = BooleanArray(terms.size)
        val withEarlier = BooleanArray(terms.size)
        val known = messages
        for (chunk in chunks) {
            for (i in 0 until chunk.size) {
                val m = chunk.message[i].toInt() and 0xFFFF
                if (m >= known.size) continue
                val start = chunk.start(i)
                var all = true
                for (term in terms.indices) {
                    if (filter.test(term, known[m], chunk.time[i], chunk.blob, start, chunk.end[i] - start, chunk.changed[i].toInt() and 0xFF)) alone[term] = true else all = false
                    if (all) withEarlier[term] = true
                }
            }
        }
        val never = alone.indexOf(false)
        if (never >= 0) return "No frame matches ${terms[never]}"
        val broken = withEarlier.indexOf(false)
        return if (broken < 0) null else "No frame matches ${terms[broken]} together with ${terms.subList(0, broken).joinToString(" ")}"
    }

    class Series(@JvmField val times: FloatArray, @JvmField val values: FloatArray, @JvmField val size: Int)

    companion object {
        const val CHUNK_BITS = 18
        const val CAPACITY = 1 shl CHUNK_BITS
        /** Memory one [Chunk] takes: time, message, end, 8 payload bytes and the change mask per frame. */
        const val CHUNK_BYTES = CAPACITY * 19L
        const val MAX_PAYLOAD = 64
        const val MICROS_PER_TICK = 100L
        const val TICKS_PER_SECOND = 10_000.0
    }
}
