package com.ehoeve.canlens.core

/** Writes held data as files other tools read, and this app reads back. */
object Export {
    /**
     * The frames of [rows] between [fromTime] and [toTime]: candump lines stamped with the source's own clock, or with
     * [csv] a `time,addr,bus,data` table (Cabana's layout) in seconds since the start. Returns how many were written.
     * A candump log also gets [markers] (store time, note; in time order) as notes, read back as markers, as a recording's are.
     */
    fun frames(store: FrameStore, rows: FrameStore.Rows, fromTime: Int, toTime: Int, csv: Boolean, out: Appendable, markers: List<Pair<Int, String>> = emptyList()): Int {
        val messages = store.messages
        val line = StringBuilder(200)
        if (csv) out.append("time,addr,bus,data\n")
        val notes = if (csv) emptyList() else markers.filter { it.first in fromTime..toTime }
        var note = 0
        fun notesUpTo(time: Int) {
            while (note < notes.size && notes[note].first <= time) {
                line.setLength(0)
                line.appendMarker(store.originMicros + notes[note].first * FrameStore.MICROS_PER_TICK, notes[note].second)
                out.append(line)
                note++
            }
        }
        var written = 0
        for (row in 0 until rows.size) {
            val position = rows.position(row)
            val chunk = rows.chunks[position ushr FrameStore.CHUNK_BITS]
            val index = position and FrameStore.CAPACITY - 1
            val time = chunk.time[index]
            if (time < fromTime || time > toTime) continue
            val message = messages.getOrNull(chunk.message[index].toInt() and 0xFFFF) ?: continue
            val start = chunk.start(index)
            notesUpTo(time)
            line.setLength(0)
            if (csv) {
                line.appendSeconds(time * FrameStore.MICROS_PER_TICK).append(",0x").append(Integer.toHexString(message.id)).append(',').append(message.bus).append(",0x")
                for (i in start until chunk.end[index]) line.append(HEX[chunk.blob[i].toInt() shr 4 and 15]).append(HEX[chunk.blob[i].toInt() and 15])
                line.append('\n')
            } else line.appendCandump(store.originMicros + time * FrameStore.MICROS_PER_TICK, message.bus, message.id, chunk.blob, start, chunk.end[index] - start)
            out.append(line)
            written++
        }
        notesUpTo(Int.MAX_VALUE)
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
