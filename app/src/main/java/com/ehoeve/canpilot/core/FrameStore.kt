package com.ehoeve.canpilot.core

/**
 * Receives CAN frames. `data[offset until offset + length]` is only valid during the call.
 * Returns false to make the producer stop.
 */
fun interface FrameSink {
    fun frame(timeMicros: Long, bus: Int, id: Int, data: ByteArray, offset: Int, length: Int): Boolean
}

/**
 * All frames of the open logs or the live stream, in arrival order, in fixed-size chunks of primitive arrays
 * (about 18 bytes a frame, no object per frame). One writer thread, any number of readers: a reader takes
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
        @Volatile @JvmField var size = 0

        fun start(index: Int) = if (index == 0) 0 else end[index - 1]
    }

    class Message(val bus: Int, val id: Int, val index: Int, @JvmField val firstTime: Int) {
        @Volatile @JvmField var count = 0L
        @Volatile @JvmField var lastTime = firstTime
        /** Payload of the latest frame; replaced, never modified. */
        @Volatile @JvmField var data = ByteArray(0)

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
        chunk.size = index + 1
        message.data = data.copyOfRange(offset, offset + length)
        message.lastTime = time
        message.count++
        lastTime = time
        frameCount++
        return true
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

    class Series(@JvmField val times: FloatArray, @JvmField val values: FloatArray, @JvmField val size: Int)

    companion object {
        const val CAPACITY = 1 shl 18
        const val MAX_PAYLOAD = 64
        const val MICROS_PER_TICK = 100L
        const val TICKS_PER_SECOND = 10_000.0
    }
}
