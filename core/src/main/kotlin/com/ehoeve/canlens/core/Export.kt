package com.ehoeve.canlens.core

/** Writes held data as files other tools read, and this app reads back. */
object Export {
    /**
     * The frames of [rows] between [fromTime] and [toTime]: candump lines stamped with the source's own clock, or with
     * [csv] a `time,addr,bus,data` table (Cabana's layout) in seconds since the start. Returns how many were written.
     */
    fun frames(store: FrameStore, rows: FrameStore.Rows, fromTime: Int, toTime: Int, csv: Boolean, out: Appendable): Int {
        val messages = store.messages
        val line = StringBuilder(200)
        if (csv) out.append("time,addr,bus,data\n")
        var written = 0
        for (row in 0 until rows.size) {
            val position = rows.position(row)
            val chunk = rows.chunks[position ushr FrameStore.CHUNK_BITS]
            val index = position and FrameStore.CAPACITY - 1
            val time = chunk.time[index]
            if (time < fromTime || time > toTime) continue
            val message = messages.getOrNull(chunk.message[index].toInt() and 0xFFFF) ?: continue
            val start = chunk.start(index)
            line.setLength(0)
            if (csv) {
                line.appendSeconds(time * FrameStore.MICROS_PER_TICK).append(",0x").append(Integer.toHexString(message.id)).append(',').append(message.bus).append(",0x")
                for (i in start until chunk.end[index]) line.append(HEX[chunk.blob[i].toInt() shr 4 and 15]).append(HEX[chunk.blob[i].toInt() and 15])
                line.append('\n')
            } else line.appendCandump(store.originMicros + time * FrameStore.MICROS_PER_TICK, message.bus, message.id, chunk.blob, start, chunk.end[index] - start)
            out.append(line)
            written++
        }
        return written
    }

    /** Decoded signals as a `time,signal,value` table, one signal after the other; [named] pairs each series with its column text. */
    fun signals(named: List<Pair<String, FrameStore.Series>>, fromSeconds: Float, toSeconds: Float, out: Appendable) {
        out.append("time,signal,value\n")
        for ((name, series) in named) {
            for (i in 0 until series.size) {
                val time = series.times[i]
                if (time < fromSeconds || time > toSeconds) continue
                out.append(time.toString()).append(',').append(name).append(',').append(series.values[i].toString()).append('\n')
            }
        }
    }

    private const val HEX = "0123456789abcdef"
}
