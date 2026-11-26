package com.ehoeve.canlens.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDecodeTest {
    /** The checksum test as it was before each frame's sum was taken once: which of the three sums byte [at] is, or null. */
    private fun checksumBefore(id: Int, p: FrameStore.Payloads, at: Int): String? {
        val idSum = (id and 0xFF) + (id ushr 8 and 0xFF)
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
        if (!varies) return null
        val needed = p.size - p.size / 100
        return when {
            teslaSum >= needed -> "Tesla"
            plainSum >= needed -> "the sum of the other bytes"
            xor >= needed -> "the XOR"
            else -> null
        }
    }

    @Test fun checksumsComeOutAsBefore() {
        val id = 0x257
        // byte 1 is the checksum of the other four, of each kind, now and then wrong in [broken] of 1000 frames
        for (kind in listOf("tesla", "plain", "xor")) for (broken in listOf(0, 5, 10, 11, 30)) {
            val store = FrameStore()
            var noise = 17
            for (i in 0 until 1000) {
                noise = noise * 1103515245 + 12345
                val body = intArrayOf(i and 15, noise ushr 8 and 0xFF, noise ushr 16 and 0xFF, noise ushr 24 and 0xFF)
                var check = when (kind) {
                    "tesla" -> body.sum() + (id and 0xFF) + (id ushr 8)
                    "plain" -> body.sum()
                    else -> body.reduce { a, b -> a xor b }
                } and 0xFF
                if (i % 97 == 3 && i / 97 < broken || i >= 1000 - (broken - 11).coerceAtLeast(0)) check = check xor 0x5A
                store.frame(i * 20_000L, 0, id, byteArrayOf(body[0].toByte(), check.toByte(), body[1].toByte(), body[2].toByte(), body[3].toByte()), 0, 5)
            }
            val before = checksumBefore(id, store.payloads(store.messages[0]), 1)
            val now = AutoDecode.run(store, { null }, emptyList()).singleOrNull { it.signal.name == "CHECKSUM_8" }
            if (before == null) assertNull("$kind, $broken wrong", now)
            else assertTrue("$kind, $broken wrong: ${now?.evidence}", now != null && before in now.evidence)
            if (broken <= 10) assertTrue("$kind, $broken wrong", before != null)
        }
    }

    @Test fun readingsWithACommaOrAUnicodeMinus() {
        assertEquals("soc" to 21.5, AutoDecode.reading("soc=21,5"))
        assertEquals("temp" to -3.5, AutoDecode.reading("temp = −3,5"))
        assertEquals("temp" to -2.0, AutoDecode.reading("temp=–2"))
        assertEquals("range" to -312.0, AutoDecode.reading("range = -312"))
        assertEquals("soc" to 78.5, AutoDecode.reading("soc = 78.5"))
        assertNull(AutoDecode.reading("door open"))
        assertNull(AutoDecode.reading("soc = 7,"))
    }

    @Test fun readingsThatOnlyFollowTheClockNeedARoundScale() {
        val store = FrameStore()
        var noise = 7
        for (i in 0 until 1000) {
            noise = noise * 1103515245 + 12345
            val odometer = 1000 + i
            store.frame(i * 20_000L, 0, 0x300, byteArrayOf(odometer.toByte(), (odometer shr 8).toByte(), (noise ushr 16).toByte()), 0, 3)
            store.frame(i * 20_000L + 5, 0, 0x310, byteArrayOf((Math.abs(i % 200 - 100) + 20).toByte()), 0, 1)
        }
        fun at(frame: Int) = frame * 200 + 3
        // rises in step with the time, at a scale no encoding has (0.5286 a step): the odometer fits it, and so would any trend
        val trend = listOf(50, 250, 480, 700, 950).map { at(it) to 11.1 + it * 0.5286 }
        assertTrue(Analysis.findByValue(store, trend, (950 - 50) * 0.5286 * 0.02, 3).any { it.message.id == 0x300 })
        assertTrue(AutoDecode.fromReadings(store, { null }, mapOf("energy" to trend)).isEmpty())
        // up and down: the same odd scale is believed (odd and even values, or the field without its low bit fits too)
        val wave = listOf(10, 61, 130, 171, 240).map { at(it) to 3.3 + (Math.abs(it % 200 - 100) + 20) * 0.537 }
        val found = AutoDecode.fromReadings(store, { null }, mapOf("level" to wave)).first()
        assertEquals(0x310, found.message.id)
        assertEquals(0.537, found.signal.factor, 1e-6)
        assertEquals(0.8, found.confidence, 0.0)
    }

    @Test fun payloadsOfManyMessagesInOnePassAreThoseOfEach() {
        val store = FrameStore()
        // over a chunk's edge; the same id on two buses, and a message whose frames are sometimes shorter
        for (i in 0 until FrameStore.CAPACITY + 5000) {
            when (i % 5) {
                0, 1 -> store.frame(i * 100L, 0, 0x100, byteArrayOf(i.toByte(), (i shr 8).toByte(), 3), 0, 3)
                2 -> store.frame(i * 100L, 1, 0x100, byteArrayOf(i.toByte(), (i shr 8).toByte(), 4), 0, 3)
                3 -> store.frame(i * 100L, 0, 0x200, byteArrayOf(i.toByte(), 1, 2, 3, 4, 5, 6, 7), 0, if (i % 7 == 0) 4 else 8)
                else -> if (i % 15 == 4) store.frame(i * 100L, 2, 0x300, byteArrayOf((i shr 4).toByte()), 0, 1)
            }
        }
        val messages = store.messages.toList()
        fun same(loaded: Map<Int, FrameStore.Payloads>, of: List<FrameStore.Message>) {
            for (message in of) {
                val each = store.payloads(message)
                val together = loaded.getValue(message.index)
                assertEquals(each.size, together.size)
                assertEquals(each.length, together.length)
                assertArrayEquals(each.times.copyOf(each.size), together.times.copyOf(together.size))
                assertArrayEquals(each.data.copyOf(each.size * each.length), together.data.copyOf(together.size * together.length))
            }
        }
        same(Analysis.payloads(store, messages), messages)
        val groups = messages.groupBy { it.id }.values.toList()
        assertEquals(1, Analysis.batches(store, groups).size)
        // a tiny budget: each id by itself, its two buses together
        val small = Analysis.batches(store, groups, budget = 1)
        assertEquals(groups, small.map { it.single() })
        for (batch in small) same(Analysis.payloads(store, batch.flatten()), batch.flatten())
    }
}
