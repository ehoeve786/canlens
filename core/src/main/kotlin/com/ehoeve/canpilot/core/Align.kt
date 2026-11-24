package com.ehoeve.canpilot.core

/**
 * Finds how far apart the clocks of two recordings of the same drive are, from the frames both of them hold.
 * A short run of consecutive payloads of one message is looked up in the other recording; where it occurs exactly
 * once, the two time stamps of its first frame give one estimate. Many runs of several messages vote, so frames one
 * recorder dropped, and stretches where a message repeats itself, do no harm.
 */
object Align {
    private const val RUN = 6
    private const val ANCHORS = 40
    private const val MESSAGES = 6
    private const val MIN_VOTES = 3
    /** Estimates this close together (in 100 µs ticks) count as agreeing: recorders stamp frames a few ms late. */
    private const val AGREE = 200

    /** Microseconds to add to [other]'s time stamps to put them on [base]'s clock, or null when the data doesn't show it. */
    fun offsetMicros(base: FrameStore, other: FrameStore): Long? {
        val votes = ArrayList<Long>()
        // messages that say the most first: many frames
        val candidates = other.messages.filter { it.count >= 50 && it.data.size >= 2 }.sortedByDescending { it.count }
        var used = 0
        for (theirs in candidates) {
            val mine = base.messages.filter { it.id == theirs.id && it.data.size == theirs.data.size }.maxByOrNull { it.count } ?: continue
            val before = votes.size
            vote(base.payloads(mine), other.payloads(theirs), base.originMicros - other.originMicros, votes)
            if (votes.size > before && ++used == MESSAGES) break
        }
        if (votes.size < MIN_VOTES) return null
        votes.sort()
        // the largest group of estimates that agree; its middle one is the answer
        var bestFrom = 0
        var bestSize = 0
        var from = 0
        for (to in votes.indices) {
            while (votes[to] - votes[from] > AGREE * FrameStore.MICROS_PER_TICK) from++
            if (to - from + 1 > bestSize) { bestSize = to - from + 1; bestFrom = from }
        }
        return if (bestSize < MIN_VOTES || bestSize * 2 < votes.size) null else votes[bestFrom + bestSize / 2]
    }

    private fun vote(mine: FrameStore.Payloads, theirs: FrameStore.Payloads, originGap: Long, votes: MutableList<Long>) {
        val length = theirs.length
        if (mine.length != length || mine.size < RUN || theirs.size < RUN) return
        val step = maxOf((theirs.size - RUN) / ANCHORS, 1)
        var anchor = 0
        while (anchor + RUN <= theirs.size) {
            val run = anchor * length
            // a run of equal frames could sit anywhere
            if (!same(theirs.data, run, theirs.data, run + length, (RUN - 1) * length)) {
                var found = -1
                var i = 0
                while (i + RUN <= mine.size) {
                    if (same(mine.data, i * length, theirs.data, run, RUN * length)) { if (found >= 0) { found = -2; break }; found = i }
                    i++
                }
                if (found >= 0) votes += originGap + (mine.times[found] - theirs.times[anchor]) * FrameStore.MICROS_PER_TICK
            }
            anchor += step
        }
    }

    private fun same(a: ByteArray, aFrom: Int, b: ByteArray, bFrom: Int, count: Int): Boolean {
        for (i in 0 until count) if (a[aFrom + i] != b[bFrom + i]) return false
        return true
    }
}
