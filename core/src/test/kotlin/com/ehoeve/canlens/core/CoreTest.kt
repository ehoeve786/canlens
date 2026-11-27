package com.ehoeve.canlens.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class CoreTest {
    private class Frame(val time: Long, val bus: Int, val id: Int, val data: ByteArray)

    private fun read(text: String) = read(text.toByteArray())
    private fun read(bytes: ByteArray): Pair<String, List<Frame>> {
        val frames = ArrayList<Frame>()
        val format = LogReader.read(bytes.inputStream(), { t, bus, id, d, o, n -> frames += Frame(t, bus, id, d.copyOfRange(o, o + n)); true })
        return format to frames
    }

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val dbc = Dbc.parse(
        """
        VERSION ""
        BO_ 280 DI_systemStatus: 8 XXX
         SG_ little : 0|16@1+ (1,0) [0|0] "" XXX
         SG_ big : 23|16@0+ (0.5,-10) [0|0] "kph" XXX
         SG_ negative : 32|8@1- (1,0) [0|0] "" XXX
         SG_ acrossBytes : 43|12@0+ (1,0) [0|0] "" XXX

        BO_ 2566844688 Extended: 8 XXX
         SG_ index M : 0|4@1+ (1,0) [0|0] "" XXX
         SG_ whenOne m1 : 8|8@1+ (1,0) [0|0] "" XXX
         SG_ whenTwo m2 : 8|8@1+ (2,0) [0|0] "" XXX

        VAL_ 280 negative -1 "REVERSE" 0 "PARK" ;
        """.trimIndent(), "test"
    )

    @Test fun dbcDecodesBothByteOrders() {
        val m = dbc.messages[280]!!
        val d = bytes("34121234FF0ABC00")
        fun value(name: String) = m.signals.first { it.name == name }.let { it.physical(it.raw(d)) }
        assertEquals(0x1234.toDouble(), value("little"), 0.0)
        assertEquals(0x1234 * 0.5 - 10, value("big"), 0.0)
        assertEquals(-1.0, value("negative"), 0.0)
        assertEquals(0xABC.toDouble(), value("acrossBytes"), 0.0)
        assertEquals("REVERSE", m.signals.first { it.name == "negative" }.valueNames[-1L])
        assertEquals("kph", m.signals.first { it.name == "big" }.unit)
        assertFalse(m.signals.first { it.name == "acrossBytes" }.fits(6))
        assertTrue(m.signals.first { it.name == "acrossBytes" }.fits(7))
        assertEquals(listOf(40, 41, 42, 43), m.signals.first { it.name == "acrossBytes" }.bits.takeLast(4))
        assertEquals(48, m.signals.first { it.name == "acrossBytes" }.bits[0])
    }

    @Test fun dbcKeepsExtendedIdsAndMultiplexing() {
        val m = dbc.messages[0x18FEF110]!!
        assertEquals("index", m.multiplexor!!.name)
        assertEquals(1, m.signals.first { it.name == "whenOne" }.muxValue)
        assertEquals(-1, m.multiplexor.muxValue)
    }

    @Test fun messagesCanBeRenamedOrNamedBeforeTheyHaveSignals() {
        val renamed = dbc.withMessageName(280, "DI_status", 8).withMessageName(0x228, "UNK_228_alive", 2)
        val back = Dbc.parse(renamed.write(), "back")
        assertEquals("DI_status", back.messages[280]!!.name)
        assertEquals(dbc.messages[280]!!.signals.size, back.messages[280]!!.signals.size)
        assertEquals("UNK_228_alive", back.messages[0x228]!!.name)
        assertEquals(2, back.messages[0x228]!!.length)
        assertEquals("UNK_228_alive", back.withSignal(0x228, "NEW_228", 2, Signal("crc", 8, 8, true, false, 1.0, 0.0, "", -1, false)).messages[0x228]!!.name)
    }

    @Test fun savingKeepsCommentsAttributesAndUntouchedMessages() {
        val text = "VERSION \"1\"\n\nBU_: VEH CH\n\nBO_ 100 A: 2 VEH\n SG_ a1 : 0|8@1+ (1,0) [0|255] \"\" CH\n\n" +
            "BO_ 200 B: 1 CH\n SG_ b1 : 0|2@1+ (1,0) [0|3] \"\" VEH\n\n" +
            "CM_ BO_ 100 \"Seen on this car:\nfit good.\";\nBA_ \"GenMsgCycleTime\" BO_ 200 100;\n" +
            "VAL_ 100 a1 0 \"OFF\" 1 \"ON\" ;\nVAL_ 200 b1 2 \"TWO\" ;\n"
        val original = Dbc.parse(text, "t")
        assertEquals(text, original.write())   // nothing changed: the same text
        val changed = original.withSignal(200, "B", 1, Signal("b2", 4, 2, true, false, 1.0, 0.0, "", -1, false))
        val written = changed.write()
        assertTrue(written.startsWith("VERSION \"1\"\n\nBU_: VEH CH\n\nBO_ 100 A: 2 VEH\n SG_ a1 : 0|8@1+ (1,0) [0|255] \"\" CH\n\n"))
        assertTrue("CM_ BO_ 100 \"Seen on this car:\nfit good.\";" in written)
        assertTrue("BA_ \"GenMsgCycleTime\" BO_ 200 100;" in written)
        assertEquals(1, Regex("VAL_ 200 b1").findAll(written).count())
        val back = Dbc.parse(written, "t")
        assertEquals(listOf("b1", "b2"), back.messages[200]!!.signals.map { it.name })
        assertEquals("TWO", back.messages[200]!!.signals[0].valueNames[2])
        assertEquals("ON", back.messages[100]!!.signals[0].valueNames[1])
        assertEquals(written, back.write())   // stable: saving again changes nothing
    }

    @Test fun dbcSurvivesWritingAndReading() {
        val added = dbc.withSignal(0x123, "NEW_123", 8, Signal("guess", 7, 10, false, false, 0.1, 5.0, "V", -1, false))
        // the writer's own text, not only what the parser makes of it
        assertTrue(added.write().contains("BO_ 291 NEW_123: 8 XXX\n SG_ guess : 7|10@0+ (0.1,5) [0|0] \"V\" XXX\n"))
        val again = Dbc.parse(added.write(), "again")
        assertEquals(added.messages.keys, again.messages.keys)
        val d = bytes("FEDCBA9876543210")
        for ((id, m) in added.messages) for (s in m.signals) {
            val other = again.messages[id]!!.signals.first { it.name == s.name }
            assertEquals(s.physical(s.raw(d)), other.physical(other.raw(d)), 0.0)
            assertEquals(s.muxValue, other.muxValue)
            assertEquals(s.valueNames, other.valueNames)
        }
        assertEquals("NEW_123", again.messages[0x123]!!.name)
        assertTrue(added.withoutSignal(0x123, "guess").messages[0x123]!!.signals.isEmpty())
    }

    @Test fun rlogGivesTheCanEventsOnly() {
        val (format, frames) = read(javaClass.getResourceAsStream("/sample.rlog")!!.readBytes())
        assertEquals("rlog", format)
        assertEquals(listOf(0x118, 0x257, 0x18DAF110, 0x118, 0x3FF), frames.map { it.id })
        assertEquals(listOf(0, 1, 2, 0, 3), frames.map { it.bus })
        assertEquals(listOf(1_000_000L, 1_000_000L, 1_000_000L, 2_000_000L, 2_000_000L), frames.map { it.time })
        assertArrayEquals(bytes("0102030405060708"), frames[0].data)
        assertArrayEquals(bytes("aabbccdd"), frames[1].data)
        assertEquals(0, frames[2].data.size)
        assertArrayEquals(ByteArray(64) { it.toByte() }, frames[4].data)   // from a message in several segments
    }

    @Test fun candumpWithDashPilotDiagnosticsAndCanFd() {
        val (format, frames) = read(
            "(1795462824.051000) can1 103#22B388068E462132\n" +
                "(1795462824.0521) diag0 1DA50001#0000000000000000\n" +
                "(1795462825.000001) vcan0 18DAF110##1AABB\n" +
                "garbage\n" +
                "(1795462826.5) can0 7FF#\n"
        )
        assertEquals("candump", format)
        assertEquals(listOf(0x103, 0x18DAF110, 0x7FF), frames.map { it.id })
        assertEquals(listOf(1, 0, 0), frames.map { it.bus })
        assertEquals(1795462824_051000L, frames[0].time)
        assertEquals(1795462826_500000L, frames[2].time)
        assertArrayEquals(bytes("AABB"), frames[1].data)
        val written = StringBuilder().apply { for (f in frames) appendCandump(f.time, f.bus, f.id, f.data, 0, f.data.size) }.toString()
        assertEquals("(1795462824.051000) can1 103#22B388068E462132\n", written.lineSequence().first() + "\n")
        assertEquals(frames.map { it.id to it.time }, read(written).second.map { it.id to it.time })
    }

    @Test fun vectorAsc() {
        val (format, frames) = read(
            "date Mon Nov 23 09:00:00 pm 2026\nbase hex  timestamps absolute\n" +
                "   0.016728 1  123             Rx   d 8 3E 42 03 00 39 00 03 01\n" +
                "   1.5 2  18DAF110x       Tx   d 2 AA BB\n" +
                "   2.0 1  Statistic: D 0 R 0\n"
        )
        assertEquals("ASC", format)
        assertEquals(listOf(0x123, 0x18DAF110), frames.map { it.id })
        assertEquals(listOf(0, 1), frames.map { it.bus })
        assertEquals(16728L, frames[0].time)
        assertArrayEquals(bytes("3E42030039000301"), frames[0].data)
    }

    @Test fun csvFromSavvyCanAndCabana() {
        val savvy = read("Time Stamp,ID,Extended,Dir,Bus,LEN,D1,D2,D3,D4,D5,D6,D7,D8\n1000500,00000118,false,Rx,1,3,0A,0B,0C,00,00,00,00,00\n").second
        assertEquals(0x118, savvy[0].id); assertEquals(1, savvy[0].bus); assertEquals(1000500L, savvy[0].time)
        assertArrayEquals(bytes("0A0B0C"), savvy[0].data)
        val cabana = read("time,addr,bus,data\n12.25,0x257,2,0xaabbccdd\n").second
        assertEquals(0x257, cabana[0].id); assertEquals(2, cabana[0].bus); assertEquals(12_250_000L, cabana[0].time)
        assertArrayEquals(bytes("aabbccdd"), cabana[0].data)
        val dashPilot = read("ms,bus,id,data\n1500,1,103,22B388068E462132\n1501,16,1DA50001,0000000000000000\n2000,0,18DAF110,AABB\n").second
        assertEquals(listOf(0x103, 0x18DAF110), dashPilot.map { it.id })            // the bus 16 row is the firmware's statistics
        assertEquals(listOf(1, 0), dashPilot.map { it.bus })
        assertEquals(listOf(1_500_000L, 2_000_000L), dashPilot.map { it.time })
        assertArrayEquals(bytes("22B388068E462132"), dashPilot[0].data)
    }

    @Test fun dashKitUsbLines() {
        val store = FrameStore()
        val buffer = ByteArray(64)
        assertTrue(DashKitLine.parse("A,0,239,0102030405060708", 5, buffer, store))
        assertTrue(DashKitLine.parse("S,0,3", 6, buffer, store))
        assertTrue(DashKitLine.parse("A,1,1,zz", 7, buffer, store))
        assertEquals(1L, store.frameCount)
        assertEquals(0x239, store.messages[0].id)
    }

    @Test fun cerealPortMatchesMsgq() = assertEquals(62300, CommaStream.cerealPort("can"))

    @Test fun storeSeriesFrameLookupAndBitActivity() {
        val store = FrameStore()
        // 0x118 counts up in byte 0 every 10 ms; bit 0 of byte 1 flips once, at 0.5 s
        for (i in 0 until 100) {
            store.frame(i * 10_000L, 0, 0x118, byteArrayOf(i.toByte(), if (i >= 50) 1 else 0), 0, 2)
            store.frame(i * 10_000L, 1, 0x257, byteArrayOf(7), 0, 1)
        }
        val message = store.messages.first { it.id == 0x118 }
        val counter = Signal("counter", 0, 8, true, false, 2.0, 0.0, "", -1, false)
        val series = store.series(message, DbcMessage(0x118, "M", 2, listOf(counter)), counter)
        assertEquals(100, series.size)
        assertEquals(0.5f, series.times[50], 1e-6f)
        assertEquals(100f, series.values[50], 0f)
        assertEquals(10, store.series(message, DbcMessage(0x118, "M", 2, listOf(counter)), counter, 1000, 1900).size)
        assertEquals(100L, message.count)
        assertEquals(100.0, message.rate, 0.01)
        assertArrayEquals(byteArrayOf(49, 0), store.frameAt(message, 4999)!!)
        assertNull(store.frameAt(message, -1))
        val window = store.bitActivity(4500, 5500)[message.index]!!
        assertEquals(1, window.inside[8]); assertEquals(0, window.outside[8])
        assertTrue(window.inside[0] > 0 && window.outside[0] > 0)
        assertEquals(1, window.onlyInside)
        assertEquals(0, store.bitActivity(4500, 5500)[store.messages.first { it.id == 0x257 }.index]!!.onlyInside)
    }

    @Test fun storeMultiplexedSeriesAndLimits() {
        val store = FrameStore(maxChunks = 1)
        val m = dbc.messages[0x18FEF110]!!
        store.frame(0, 0, m.id, byteArrayOf(1, 10), 0, 2)
        store.frame(100, 0, m.id, byteArrayOf(2, 10), 0, 2)
        store.frame(200, 0, m.id, byteArrayOf(1, 30), 0, 2)
        val one = store.series(store.messages[0], m, m.signals.first { it.name == "whenOne" })
        assertEquals(listOf(10f, 30f), one.values.take(one.size))
        val two = store.series(store.messages[0], m, m.signals.first { it.name == "whenTwo" })
        assertEquals(listOf(20f), two.values.take(two.size))
        // a log stops at the limit; a live stream drops its oldest chunk instead
        val data = ByteArray(8)
        var accepted = 0
        val before = store.frameCount
        while (store.frame(1000, 0, 1, data, 0, 8)) accepted++
        assertTrue(store.truncated)
        assertEquals(FrameStore.CAPACITY * store.maxChunks - before, accepted.toLong())
        val live = FrameStore(maxChunks = 2).apply { dropOldest = true }
        for (i in 0 until FrameStore.CAPACITY * 3) live.frame(i * 100L, 0, 1, data, 0, 8)
        assertFalse(live.truncated)
        assertEquals(2, live.chunks.size)
        assertEquals(FrameStore.CAPACITY, live.firstTime)
    }

    /** A log with a speed (x0.1, 12 bits of room) in 0x118 bits 4..15, a 4-bit counter and a checksum-like byte in 0x257, and a door bit in 0x300. */
    private fun analysisStore(doorOpen: Boolean = false): FrameStore {
        val store = FrameStore()
        var noise = 12345
        for (i in 0 until 600) {
            val speed = (300 + 250 * Math.sin(i / 60.0)).toInt()            // raw, 5..55 in physical units
            store.frame(i * 20_000L, 0, 0x118, byteArrayOf((speed shl 4 and 0xF0 or 5).toByte(), (speed shr 4).toByte(), 0), 0, 3)
            noise = noise * 1103515245 + 12345
            store.frame(i * 20_000L + 5, 1, 0x257, byteArrayOf((i and 15).toByte(), (noise ushr 16).toByte()), 0, 2)
            if (i % 10 == 0) store.frame(i * 20_000L + 7, 0, 0x300, byteArrayOf(if (doorOpen) 0x44 else 0x40, (speed / 3).toByte()), 0, 2)
        }
        return store
    }

    @Test fun findsAFieldByItsValueAtKnownTimes() {
        val store = analysisStore()
        fun speedAt(i: Int) = (300 + 250 * Math.sin(i / 60.0)).toInt() * 0.1
        val points = listOf(100, 300, 500).map { it * 200 + 3 to speedAt(it) }
        val found = Analysis.findByValue(store, points, 0.05)
        val best = found.first()
        assertEquals(0x118, best.message.id)
        assertEquals(4, best.signal.start); assertEquals(10, best.signal.size)   // the two top bits of the 12 are never set
        assertEquals(0.1, best.signal.factor, 1e-6)
        // one point: only the common factors are tried
        assertTrue(Analysis.findByValue(store, points.take(1), 0.05).any { it.message.id == 0x118 && it.signal.start == 4 && it.signal.factor == 0.1 })
        assertTrue(Analysis.findByValue(store, listOf(0 to 123456.0), 0.5).isEmpty())
    }

    @Test fun findsFieldsThatMoveWithAKnownSignal() {
        val store = analysisStore()
        val speedMessage = store.messages.first { it.id == 0x118 }
        val speed = Signal("speed", 4, 12, true, false, 0.1, 0.0, "kph", -1, false)
        val reference = store.series(speedMessage, DbcMessage(0x118, "", 3, listOf(speed)), speed)
        val found = Analysis.findCorrelated(store, reference, speedMessage)
        assertEquals(0x300, found.first().message.id)
        assertEquals(8, found.first().signal.start)
        assertTrue(found.first().score > 0.99)
        assertEquals(0.3, found.first().signal.factor, 0.02)                 // scaled to the reference's units
        assertTrue(found.none { it.message.id == 0x118 || it.message.id == 0x257 })
    }

    @Test fun guessesCountersChecksumsAndFields() {
        val store = analysisStore()
        val guesses = Analysis.guessFields(store, store.messages.first { it.id == 0x257 })
        assertEquals(listOf("COUNTER_0", "CHECKSUM_8"), guesses.map { it.name })
        assertEquals(listOf(4, 8), guesses.map { it.size })
        val speed = Analysis.guessFields(store, store.messages.first { it.id == 0x118 })
        assertEquals(listOf(4), speed.map { it.start })
        assertTrue(speed[0].size in 10..12)
    }

    @Test fun comparesTwoLogsBitByBit() {
        val closed = BitSummary().also { analysisStore(false).replay(it) }
        val open = BitSummary().also { analysisStore(true).replay(it) }
        val door = closed.differences(open, 0, 0x300, 16)!!
        assertEquals(BitSummary.DIFFERENT, door[2])
        assertEquals(1, door.count { it != 0 })
        assertEquals(0, closed.differences(open, 0, 0x118, 24)!!.count { it != 0 })
        assertNull(closed.differences(open, 3, 0x999, 8))
        val still = BitSummary().also { it.frame(0, 0, 0x300, byteArrayOf(0x40, 7), 0, 2); it.frame(1, 0, 0x300, byteArrayOf(0x40, 7), 0, 2) }
        assertTrue(closed.differences(still, 0, 0x300, 16)!!.drop(8).any { it == BitSummary.VARIES_IN_ONE })
    }

    @Test fun histogramAndSnapshotsAndPayloads() {
        val store = analysisStore()
        val counterMessage = store.messages.first { it.id == 0x257 }
        val counter = Signal("c", 0, 4, true, false, 1.0, 0.0, "", -1, false)
        val histogram = Analysis.histogram(store.series(counterMessage, DbcMessage(0x257, "", 2, listOf(counter)), counter))
        assertEquals((0..15).map { it.toString() }, histogram.map { it.label })
        assertEquals(600, histogram.sumOf { it.count })
        assertTrue(histogram.all { it.first >= 0f })
        val noise = Signal("n", 8, 8, true, false, 1.0, 0.0, "", -1, false)
        assertEquals(20, Analysis.histogram(store.series(counterMessage, DbcMessage(0x257, "", 2, listOf(noise)), noise)).size)
        val snapshots = store.snapshots(intArrayOf(-5, 0, 401))
        val m = counterMessage.index
        assertEquals(listOf(false, true, true), snapshots.valid[m].toList())
        assertEquals(2, snapshots.data[m][2 * 2].toInt())                        // frame i=2 at 400 ticks
        val payloads = store.payloads(counterMessage)
        assertEquals(600, payloads.size); assertEquals(2, payloads.length)
        assertEquals(15, payloads.data[15 * 2].toInt()); assertEquals(3000, payloads.times[15])
    }

    @Test fun markersInARecording() {
        val text = StringBuilder().apply {
            appendMarker(5_000_001, "first line")
            appendCandump(5_000_000, 0, 0x118, byteArrayOf(1), 0, 1)
            appendMarker(6_500_000, "opened, the door\nnow")
            appendCandump(7_000_000, 0, 0x118, byteArrayOf(2), 0, 1)
        }.toString()
        val markers = ArrayList<Pair<Long, String>>()
        val frames = ArrayList<Long>()
        val format = LogReader.read(text.byteInputStream(), { t, _, _, _, _, _ -> frames += t; true }, { t, note -> markers += t to note })
        assertEquals("candump", format)
        assertEquals(listOf(5_000_000L, 7_000_000L), frames)
        assertEquals(listOf(5_000_001L to "first line", 6_500_000L to "opened, the door now"), markers)
    }

    @Test fun namesAFieldAfterTheRateOfChangeOfASignal() {
        val store = FrameStore()
        for (i in 0 until 2000) {
            val t = i * 20_000L
            val speed = (300 + 250 * Math.sin(i / 150.0)).toInt()
            store.frame(t, 0, 0x118, byteArrayOf((speed shl 4 and 0xF0).toByte(), (speed shr 4).toByte()), 0, 2)
            store.frame(t + 1, 0, 0x402, byteArrayOf((128 + 100 * Math.cos(i / 150.0)).toInt().toByte()), 0, 1)      // the acceleration
        }
        val speed = Signal("DI_vehicleSpeed", 4, 12, true, false, 0.1, 0.0, "kph", -1, false)
        val found = AutoDecode.run(store, { if (it.id == 0x118) DbcMessage(0x118, "DI_speed", 2, listOf(speed)) else null }, emptyList())
        val accel = found.single { it.message.id == 0x402 }
        assertEquals("LIKE_RATE_DI_vehicleSpeed_0", accel.signal.name)
        assertEquals("kph/s", accel.signal.unit)
    }

    @Test fun findsBitsThatFlipTogether() {
        val store = FrameStore()
        for (i in 0 until 1000) {
            val door = if ((i / 100) % 2 == 1) 1 else 0
            store.frame(i * 20_000L, 0, 0x101, byteArrayOf(door.toByte()), 0, 1)
            store.frame(i * 20_000L + 5_000, 1, 0x202, byteArrayOf(0, (door shl 3 or (i and 1 shl 6)).toByte()), 0, 2)     // the door again, and a bit that toggles all the time
        }
        val similar = Analysis.similarBits(store, store.messages.first { it.id == 0x101 }, 0)
        assertEquals(0x202, similar.first().message.id)
        assertEquals(11, similar.first().bit)
        assertTrue(similar.first().score > 0.99)
        assertTrue(similar.none { it.bit == 14 })
    }

    @Test fun tidiesFittedScales() {
        assertEquals(0.1 to 0.0, Analysis.tidy(0.0995, 0.03))
        assertEquals(0.5 to -40.0, Analysis.tidy(0.5012, -39.8))
        assertEquals(-1.0 to 1.0, Analysis.tidy(-0.98, 0.99))
        assertEquals(0.188 to 0.05, Analysis.tidy(0.188, 0.05))                    // not near a round factor: left as fitted
    }

    @Test fun findsACrcWithADataIdPerCounterValue() {
        // AUTOSAR CRC-8 (polynomial 2F, start FF, final XOR FF) over a data id byte the counter picks, then the payload
        fun crc(bytes: List<Int>): Int {
            var c = 0xFF
            for (byte in bytes) { c = c xor byte; repeat(8) { c = if (c and 0x80 != 0) (c shl 1 xor 0x2F) and 0xFF else c shl 1 and 0xFF } }
            return c xor 0xFF
        }
        val store = FrameStore()
        var noise = 7
        for (i in 0 until 600) {
            noise = noise * 1103515245 + 12345
            val body = listOf(i and 15, noise ushr 8 and 0xFF, noise ushr 16 and 0xFF, 0x40)
            store.frame(i * 20_000L, 0, 0x3A, (listOf(crc(listOf(0x10 + (i and 15) * 7) + body)) + body).map { it.toByte() }.toByteArray(), 0, 5)
        }
        val found = AutoDecode.run(store, { null }, emptyList())
        assertTrue(found.single { it.signal.start == 0 }.evidence.contains("polynomial 2F"))
        assertEquals("COUNTER_8", found.single { it.signal.start == 8 }.signal.name)
    }

    @Test fun tellsCopiesStatesEventsAndNotAvailableApart() {
        val store = FrameStore()
        var noise = 7
        for (i in 0 until 600) {
            noise = noise * 1103515245 + 12345
            val awake = i / 60 % 2 == 1
            val level = (if (awake) 150 else 100) + (noise ushr 16 and 7)
            store.frame(i * 20_000L, 0, 0x100, byteArrayOf(level.toByte(), 0), 0, 2)
            // the level again and a counter 0..2 (byte 3 is in the DBC); in another message, a state that is 3 while the level is up
            store.frame(i * 20_000L + 5, 0, 0x200, byteArrayOf(level.toByte(), 0, (i % 3).toByte(), 0), 0, 4)
            store.frame(i * 20_000L + 7, 0, 0x201, byteArrayOf(if (awake) 3 else 0), 0, 1)
            // two flags in different messages that flip once, together
            store.frame(i * 20_000L + 10, 0, 0x300, byteArrayOf(if (i >= 333) 1 else 0), 0, 1)
            store.frame(i * 20_000L + 15, 0, 0x301, byteArrayOf(if (i >= 333) 4 else 0), 0, 1)
            // 16 bits that read all ones until the sensor wakes
            val reading = if (i < 50) 0xFFFF else 1000 + i % 200 * 3
            store.frame(i * 20_000L + 20, 0, 0x400, byteArrayOf(reading.toByte(), (reading shr 8).toByte()), 0, 2)
        }
        val dbc = Dbc.parse("BO_ 256 SRC: 2 X\n SG_ SRC_level : 0|8@1+ (0.5,-40) [0|0] \"C\" X\n\nBO_ 512 DST: 4 X\n SG_ DST_known : 24|8@1+ (1,0) [0|0] \"\" X\n", "d")
        val found = AutoDecode.run(store, { dbc.messages[it.id] }, emptyList())
        fun at(id: Int, start: Int) = found.single { it.message.id == id && it.signal.start == start }
        val copy = at(0x200, 0)
        assertEquals("COPY_SRC_level_0", copy.signal.name)
        assertEquals(0.5, copy.signal.factor, 0.0)
        assertEquals("C", copy.signal.unit)
        val state = at(0x201, 0)
        assertEquals("STATE_0", state.signal.name)                                                              // not LIKE_SRC_level: 2 values against many
        assertTrue("SRC_level" in state.evidence && state.confidence >= 0.7)
        assertEquals("DSTCounter", at(0x200, 16).signal.name)                                                   // called after its message
        val first = at(0x300, 0)
        val second = at(0x301, 2)
        assertEquals("E1_FLAG_0", first.signal.name)
        assertEquals("E1_FLAG_2", second.signal.name)
        assertEquals(66_600, first.at)                                                                          // frame 333, to jump to
        assertEquals(mapOf(0xFFFFL to "SNA"), at(0x400, 0).signal.valueNames)
    }

    @Test fun findsTheFieldBehindNumbersReadOffTheScreen() {
        val store = FrameStore()
        var noise = 7
        // byte 1: the state of charge in half percents, falling; byte 0 is noise
        for (i in 0 until 1000) { noise = noise * 1103515245 + 12345; store.frame(i * 20_000L, 1, 0x292, byteArrayOf((noise ushr 16).toByte(), (180 - i / 10).toByte(), 0), 0, 3) }
        fun reading(frame: Int) = frame * 200 + 3 to (180 - frame / 10) * 0.5
        assertEquals("soc" to "78.5", AutoDecode.READING.find("soc = 78.5")!!.let { it.groupValues[1] to it.groupValues[2] })
        assertTrue(AutoDecode.fromReadings(store, { null }, mapOf("soc" to listOf(100, 500, 900).map(::reading))).isEmpty())   // three fit anything
        val found = AutoDecode.fromReadings(store, { null }, mapOf("soc" to listOf(50, 250, 480, 700, 950).map(::reading)))
        val best = found.first()
        assertEquals("READ_SOC_8", best.signal.name)
        assertEquals(8, best.signal.start)
        assertEquals(0.5, best.signal.factor, 1e-9)
        assertEquals(0.0, best.signal.offset, 1e-9)
    }

    @Test fun noSecondCounterWhereTheDbcHasOne() {
        val store = FrameStore()
        for (i in 0 until 400) store.frame(i * 20_000L, 1, 0x26D, byteArrayOf((i and 3).toByte(), ((i + 2) and 3).toByte()), 0, 2)
        val dbc = Dbc.parse("BO_ 621 UNK_26D: 2 X\n SG_ UNK_26D_counter : 0|2@1+ (1,0) [0|0] \"\" X\n", "d")
        assertTrue("without the DBC the second counter is found", AutoDecode.run(store, { null }, emptyList()).any { it.signal.name.startsWith("COUNTER") && it.signal.start == 8 })
        assertTrue(AutoDecode.run(store, { dbc.messages[it.id] }, emptyList()).none { "Counter" in it.signal.name || it.signal.name.startsWith("COUNTER") })
    }

    @Test fun communitySignalsTheFramesContradictAreMarked() {
        val store = FrameStore()
        for (i in 0 until 200) store.frame(i * 20_000L, 1, 0x25D, byteArrayOf((i and 1).toByte(), 0), 0, 2)
        val community = Dbc.parse("BO_ 605 ID25DCP_status: 2 X\n SG_ CP_doorOpen : 0|1@1+ (1,0) [0|0] \"\" X\n SG_ CP_latch : 8|1@1+ (1,0) [0|0] \"\" X\n", "c")
        val found = AutoDecode.fromCommunity(store, { null }, listOf("model3dbc" to community))
        assertEquals(2, found.size)
        assertTrue(found.all { it.confidence < 0.7 && "doesn't fit" in it.evidence })                           // the flag is a counter's bit, so its neighbour is doubted too
        val still = FrameStore()
        for (i in 0 until 200) still.frame(i * 20_000L, 1, 0x25D, byteArrayOf(if (i > 100) 1 else 0, 0), 0, 2)
        val fitting = AutoDecode.fromCommunity(still, { null }, listOf("model3dbc" to community))
        assertEquals(2, fitting.size)
        assertTrue(fitting.all { it.confidence >= 0.7 })
    }

    @Test fun keepsSignalStatusesInTheFile() {
        val dbc = Dbc.parse("BO_ 100 A: 8 X\n SG_ S : 0|8@1+ (1,0) [0|0] \"\" X\n\nCM_ SG_ 100 S \"from the maker\";\n", "t.dbc")
        val marked = Dbc.parse(dbc.withStatus(100, "S", "plotted").withStatus(100, "S", "confirmed").write(), "t.dbc")
        assertEquals(mapOf((100 to "S") to "confirmed"), marked.statuses)
        assertTrue(marked.write().contains("from the maker"))                                                    // the file's own comment stays
        assertTrue(Dbc.parse(marked.withoutSignal(100, "S").write(), "t.dbc").statuses.isEmpty())
        assertEquals(mapOf((100 to "S") to "plotted"), Dbc.parse(dbc.write() + "CM_ SG_ 100 S \"CanPilot: plotted\";\n", "t.dbc").statuses)  // files from before the rename
    }

    @Test fun takesTheHalvesAndRepeatsOfAnActionAsOneTest() {
        val store = FrameStore()
        for (i in 0 until 1000) {
            val open = i in 200 until 400 || i in 600 until 800
            store.frame(i * 20_000L, 0, 0x401, byteArrayOf(if (open) 1 else 0, if (i in 600 until 800) 1 else 0), 0, 2)    // the door, and a bit for the second time only
        }
        val markers = listOf(200 * 200 to "door open", 400 * 200 to "door closed", 600 * 200 to "door open", 800 * 200 to "Door Closed")
        val found = AutoDecode.run(store, { null }, markers).filter { it.signal.name.startsWith("EVT_") }
        assertEquals(listOf("EVT_DOOR_0"), found.map { it.signal.name })                                   // byte 1 changed at only two of the four
        assertTrue(found.single().evidence.contains("all 4"))
    }

    @Test fun decodesAWholeBusAutomatically() {
        val store = FrameStore()
        var noise = 99
        for (i in 0 until 800) {
            val t = i * 20_000L
            val speed = (300 + 250 * Math.sin(i / 60.0)).toInt()
            store.frame(t, 0, 0x118, byteArrayOf((speed shl 4 and 0xF0).toByte(), (speed shr 4).toByte()), 0, 2)             // decoded by the DBC
            store.frame(t + 1, 1, 0x400, byteArrayOf(0, (speed / 3).toByte(), if (i in 395..400) 1 else 0), 0, 3)           // the speed again, and a bit for the marker
            noise = noise * 1103515245 + 12345
            val body = byteArrayOf((i and 15).toByte(), (noise ushr 16).toByte(), (noise ushr 24).toByte())
            val sum = body.sumOf { it.toInt() and 0xFF } + 0x57 + 0x02
            store.frame(t + 2, 0, 0x257, body + sum.toByte(), 0, 4)                                                          // counter, noise, Tesla checksum
            val torque = (20 * Math.sin(i / 40.0)).toInt()                                                                  // crosses zero
            store.frame(t + 3, 0, 0x108, byteArrayOf((torque and 0xFF).toByte(), (torque shr 8 and 0x0F).toByte()), 0, 2)
            val page = i % 3                                                                                                // multiplexed: byte 1 means something else per page
            val paged = when (page) { 0 -> i / 100; 1 -> 200 - i / 50; else -> 0xAA }
            store.frame(t + 4, 0, 0x3D2, byteArrayOf(page.toByte(), paged.toByte(), (i / 7 and 0x3F).toByte()), 0, 3)
        }
        val speed = Signal("DI_vehicleSpeed", 4, 12, true, false, 0.1, 0.0, "kph", -1, false)
        val dbc = DbcMessage(0x118, "DI_speed", 2, listOf(speed))
        val found = AutoDecode.run(store, { if (it.id == 0x118) dbc else null }, listOf(397 * 200 to "opened the door!"))
        fun of(id: Int) = found.filter { it.message.id == id }
        assertTrue(of(0x118).isEmpty())                                           // already decoded: left alone
        val copy = of(0x400).first { it.signal.start == 8 }
        assertEquals("LIKE_DI_vehicleSpeed_8", copy.signal.name)
        assertEquals("kph", copy.signal.unit)
        assertEquals(0.3, copy.signal.factor, 0.01)
        assertTrue(copy.confidence > 0.99)
        val event = of(0x400).first { it.signal.start == 16 }
        assertEquals("EVT_OPENED_THE_DOOR_16", event.signal.name)
        assertEquals(1, event.signal.size)
        assertEquals(listOf("CHECKSUM_24", "COUNTER_0"), of(0x257).filter { it.confidence >= 0.9 }.map { it.signal.name }.sorted())
        assertTrue(of(0x257).first { it.signal.name == "CHECKSUM_24" }.evidence.contains("Tesla"))
        val torque = of(0x108).single()
        assertTrue(torque.signal.signed)
        assertEquals(0, torque.signal.start)
        val mux = of(0x3D2)
        val selector = mux.first { it.signal.isMultiplexor }
        assertEquals(0, selector.signal.start); assertEquals(2, selector.signal.size)
        assertEquals(setOf(0, 1), mux.filter { it.signal.muxValue >= 0 }.map { it.signal.muxValue }.toSet())   // page 2 is constant
        assertTrue(mux.filter { it.signal.muxValue >= 0 }.all { it.signal.start in 8..15 })
        assertTrue(mux.any { it.signal.muxValue < 0 && !it.signal.isMultiplexor && it.signal.start == 16 })   // byte 2 is the same on every page
        assertEquals(found.map { it.confidence }.sortedDescending(), found.map { it.confidence })
    }

    /** Bus 0: 0x118 every 10 ms with a counter in byte 0 and a state in byte 1; bus 1: 0x249 every 100 ms, constant. */
    private fun traceStore(seconds: Int = 2): FrameStore {
        val store = FrameStore()
        for (i in 0 until seconds * 100) {
            store.frame(i * 10_000L, 0, 0x118, byteArrayOf(i.toByte(), (if (i in 50..59) 3 else 2).toByte(), 0, 0, 0, 0, 0, 0), 0, 8)
            if (i % 10 == 0) store.frame(i * 10_000L + 5, 1, 0x249, bytes("29551000"), 0, 4)
        }
        return store
    }

    private val traceDbc = Dbc.parse(
        """
        BO_ 280 DI_systemStatus: 8 XXX
         SG_ DI_counter : 0|8@1+ (1,0) [0|0] "" XXX
         SG_ DI_state : 8|3@1+ (1,0) [0|0] "" XXX
        BO_ 585 SCCM_leftStalk: 4 XXX
         SG_ SCCM_washWipe : 14|2@1+ (1,0) [0|0] "" XXX
        VAL_ 280 DI_state 2 "STANDBY" 3 "FAULT" ;
        """.trimIndent(), "trace",
    )

    private fun count(store: FrameStore, filter: String): Int =
        store.RowScan(Filter.compile(filter, store.messages) { traceDbc.messages[it.id] }).update().size

    @Test fun filterByFieldsBytesSignalsAndNames() {
        val store = traceStore()
        assertEquals(220, count(store, ""))
        assertEquals(20, count(store, "bus=1"))
        assertEquals(20, count(store, "bus = 1 id : 249"))
        assertEquals(20, count(store, "bus=1, id=249,d0=29 , t<73."))
        assertEquals(200, count(store, "id=100-1FF"))
        assertEquals(20, count(store, "len<8"))
        assertEquals(10, count(store, "d1=3"))
        assertEquals(30, count(store, "d[1]&01==1"))
        assertEquals(190, count(store, "id=118 d1!=3"))
        assertEquals(10, count(store, "DI_state=FAULT"))
        assertEquals(10, count(store, "di_state>2"))
        assertEquals(190, count(store, "DI_state=standby"))
        assertEquals(30, count(store, "DI_state=3 or bus=1"))
        assertEquals(2, count(store, "(DI_state=3 or bus=1) t=0.5-0.5049"))
        assertEquals(210, count(store, "!d1=3"))
        assertEquals(200, count(store, "not (bus=1)"))
        assertEquals(20, count(store, "stalk"))
        assertEquals(20, count(store, "249"))
        assertEquals(200, count(store, "counter"))
        assertEquals(199, count(store, "changed"))   // every 0x118 frame but its first; 0x249 never changes
        assertEquals(11, count(store, "t<0.1"))
        for (wrong in listOf("bus=", "bus=x", "(bus=1", "bus=1)", "nothing=1", "bus&1=1", "bus<1-2", "or", "bus=1 and"))
            assertTrue(wrong, runCatching { count(store, wrong) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun saysWhichTermLeavesAFilterEmpty() {
        val store = traceStore()
        fun why(text: String) = store.whyEmpty(Filter.compile(text, store.messages) { traceDbc.messages[it.id] }!!)
        assertEquals("No frame matches bus=5", why("id=118, bus=5 d1=3"))
        assertEquals("No frame matches d1=3 together with bus=1", why("bus=1 and d1=3 changed"))
        assertEquals("No frame matches ! ( DI_state=2 or len=4 ) together with id=118 not d1=3", why("id=118 not d1=3 !(DI_state=2 or len=4)"))
        assertNull(why("bus=5"))
        assertNull(why("bus=5 or bus=7"))
        assertNull(why("bus=1 id=249"))
        assertEquals(listOf("bus=1", "( id=249 or id=118 )", "not changed"), Filter.compile("bus = 1 (id=249 or id=118) && not changed", store.messages) { null }!!.terms)
    }

    @Test fun exportsFramesAndSignalsThatReadBack() {
        val store = traceStore(1)
        val rows = store.RowScan(Filter.compile("bus=1", store.messages) { null }).update()
        val log = StringBuilder()
        assertEquals(5, Export.frames(store, rows, 0, 4_100, false, log))
        val back = read(log.toString()).second
        assertEquals(listOf(0L, 100_000L, 200_000L, 300_000L, 400_000L), back.map { it.time })   // times are kept to 100 µs
        assertTrue(back.all { it.bus == 1 && it.id == 0x249 && it.data.contentEquals(bytes("29551000")) })
        val csv = StringBuilder()
        assertEquals(110, Export.frames(store, store.RowScan(null).update(), Int.MIN_VALUE, Int.MAX_VALUE, true, csv))
        val (format, frames) = read(csv.toString())
        assertEquals("CSV", format)
        assertEquals(110, frames.size)
        assertEquals(listOf(0x118, 0x249, 0x118), frames.take(3).map { it.id })
        assertEquals(990_000L, frames.last().time)
        assertArrayEquals(byteArrayOf(99, 2, 0, 0, 0, 0, 0, 0), frames.last().data)
        val table = StringBuilder()
        val message = store.messages[0]
        Export.signals(listOf("DI_state" to store.series(message, traceDbc.messages[0x118]!!, traceDbc.messages[0x118]!!.signals[1])), 0.5f, 0.515f, table)
        assertEquals("time,signal,value\n0.5,DI_state,3.0\n0.51,DI_state,3.0\n", table.toString())
    }

    /** A message whose payload never repeats: a counter and a value that wanders. */
    private fun wandering(store: FrameStore, startMicros: Long, bus: Int, keep: (Int) -> Boolean) {
        var value = 12345
        for (i in 0 until 3000) {
            value = value * 1103515245 + 12345
            if (keep(i)) store.frame(startMicros + i * 10_000L, bus, 0x129, byteArrayOf(i.toByte(), (value shr 16).toByte(), (value shr 8).toByte(), (i and 15).toByte()), 0, 4)
            if (i % 10 == 0 && keep(i)) store.frame(startMicros + i * 10_000L + 300, bus, 0x3C2, bytes("0055"), 0, 2)
        }
    }

    @Test fun alignsAndMergesTwoRecordingsOfOneDrive() {
        val car = FrameStore()
        wandering(car, 1_000_000_000L, 0) { true }
        val phone = FrameStore()
        // another clock (5.4321 s ahead), another bus number, started 2 s later, and every 37th frame lost
        wandering(phone, 1_005_432_100L, 3) { it >= 200 && it % 37 != 0 }
        val shift = Align.offsetMicros(car, phone)!!
        assertEquals(-5_432_100L, shift)
        assertNull(Align.offsetMicros(car, FrameStore().apply { for (i in 0 until 100) frame(i * 1000L, 0, 0x129, bytes("00000000"), 0, 4) }))
        val before = car.frameCount
        assertEquals(0, car.merge(phone, shift, 10))
        assertEquals(before + phone.frameCount, car.frameCount)
        assertEquals(listOf(0 to 0x129, 0 to 0x3C2, 13 to 0x129, 13 to 0x3C2), car.messages.map { it.bus to it.id })
        val both = car.RowScan(Filter.compile("id=129 t=5-5.0101", car.messages) { null }).update()
        assertEquals(4, both.size)   // frames 500 and 501 of either recording, side by side
        assertEquals(listOf(50_000, 50_000, 50_100, 50_100), (0 until 4).map { both.time(it) })
        var last = Int.MIN_VALUE
        val all = car.RowScan(null).update()
        for (row in 0 until all.size) { assertTrue(all.time(row) >= last); last = all.time(row) }
        // a recording that starts earlier moves time 0: older times shift by the difference
        val early = FrameStore().apply { frame(999_000_000L, 0, 0x55, bytes("01"), 0, 1) }
        assertEquals(10_000, car.merge(early, 0, 20))
        assertEquals(0x55, car.messages[0].id)
    }

    @Test fun dashKitTransmitLinesStatusAndGuards() {
        assertEquals("T,1,249,ce0f0000\n", DashKitLine.transmit(1, 0x249, bytes("CE0F0000")))
        assertNull(DashKitLine.transmit(2, 0x249, bytes("00")))
        assertNull(DashKitLine.transmit(0, 0x800, bytes("00")))
        assertNull(DashKitLine.transmit(0, 0x100, ByteArray(9)))
        assertNull(DashKitLine.transmit(0, 0x100, ByteArray(0)))
        val status = DashKitStatus()
        status.read("A,0,249,00")
        status.read("H,1,12,3,4,0,0")
        assertTrue(status.armed && status.sent == 12L && status.refused == 3L && status.expired == 4L)
        assertTrue("no bus reported yet: not healthy", status.unhealthy)
        status.read("D,0,1da50006,0000000000000000")
        assertTrue("bus 1 not reported yet", status.unhealthy)
        status.read("D,1,1da50006,0102000000000000")
        assertFalse(status.unhealthy)
        status.read("D,1,1da50006,8a7f200102030405")
        val bus1 = status.buses[1]!!
        assertEquals(listOf(0x8A, 0x7F, 0x20), listOf(bus1.transmitErrors, bus1.receiveErrors, bus1.flags))
        assertTrue(status.unhealthy)
        status.read("D,1,1da50001,ffffffffffffffff")   // another record: not the error state
        status.read("D,7,1da50006,0000000000000000")
        status.read("H,x")
        assertEquals(0x8A, status.buses[1]!!.transmitErrors)
        for (name in listOf("EPAS3P_status", "SCCM_leftStalk", "DI_torque", "ESP_brakeTorque", "RCM_inertial1", "IBST_status", "DI_systemStatus")) assertTrue(name, TxGuard.critical(name))
        for (name in listOf("VCLEFT_doorStatus", "UI_gpsVehicleSpeed", "VCRIGHT_hvacStatus", "BMS_hvBusStatus")) assertFalse(name, TxGuard.critical(name))
        val gears = Dbc.parse("BO_ 280 DI_systemStatus: 3 XXX\n SG_ DI_gear : 21|3@1+ (1,0) [0|0] \"\" XXX\nVAL_ 280 DI_gear 1 \"DI_GEAR_P\" 4 \"DI_GEAR_D\" ;\n", "g")
        val store = FrameStore()
        assertNull(TxGuard.inPark(store) { gears.messages[it.id] })
        store.frame(0, 0, 0x118, bytes("000020"), 0, 3)
        assertEquals(true, TxGuard.inPark(store) { gears.messages[it.id] })
        store.frame(1000, 0, 0x118, bytes("000080"), 0, 3)
        assertEquals(false, TxGuard.inPark(store) { gears.messages[it.id] })
        store.frame(2000, 0, 0x118, bytes("0000E0"), 0, 3)   // a value without a name: unknown
        assertNull(TxGuard.inPark(store) { gears.messages[it.id] })
    }

    @Test fun findsTheCommaByItsZeroMqGreeting() {
        // on 127.0.0.1 only (other loopback addresses aren't there on every OS): a publisher, a server that says
        // nothing, one that says something else, each on its own port
        val loopback = InetAddress.getByName("127.0.0.1")
        fun server(answer: ByteArray?) = java.net.ServerSocket(0, 8, loopback).also { server ->
            Thread {
                while (!server.isClosed) runCatching { server.accept().use { if (answer == null) Thread.sleep(1000) else it.getOutputStream().write(answer) } }
            }.apply { isDaemon = true }.start()
        }
        val publisher = server(ByteArray(64).also { it[0] = 0xFF.toByte(); it[9] = 0x7F; it[10] = 3 })
        val silent = server(null)
        val other = server("HTTP/1.1 400\r\n\r\n".toByteArray())
        try {
            assertTrue(CommaFinder.publishes(loopback, publisher.localPort))
            assertFalse(CommaFinder.publishes(loopback, silent.localPort))
            assertFalse(CommaFinder.publishes(loopback, other.localPort))
            // the search itself, over addresses with a stand-in test: the one that answers, none, nothing to search
            val candidates = (1..6).map { InetAddress.getByAddress(byteArrayOf(10, 0, 0, it.toByte())) }
            assertEquals(candidates[2], CommaFinder.find(candidates, 0) { host, _ -> host == candidates[2] })
            assertNull(CommaFinder.find(candidates, 0) { _, _ -> false })
            assertNull(CommaFinder.find(emptyList(), 0))
        } finally {
            publisher.close(); silent.close(); other.close()
        }
    }

    @Test fun autoDecodeSkipsCopiesAndSignalsRepeatedInTheirOwnFrame() {
        val dbc = Dbc.parse("BO_ 256 M: 2 XXX\n SG_ level : 0|8@1+ (1,0) [0|0] \"\" XXX\n", "d")
        val store = FrameStore()
        for (i in 0 until 400) {
            val level = (i / 20).toByte()
            // byte 1 repeats byte 0 in the same frame; bus 130 is the comma's echo of bus 2
            val frame = byteArrayOf(level, level)
            store.frame(i * 10_000L, 2, 0x100, frame, 0, 2)
            store.frame(i * 10_000L + 1, 130, 0x100, frame, 0, 2)
        }
        val found = AutoDecode.run(store, { if (it.id == 0x100) dbc.messages[0x100] else null }, emptyList())
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { it.message.bus == 2 })
        assertTrue(found.none { it.signal.name.startsWith("LIKE_") })
    }

    @Test fun readsCandumpLinesFromTcp() {
        val server = java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        val frames = ArrayList<Frame>()
        try {
            Thread { runCatching { server.accept().use { it.getOutputStream().write("(12.000100) can3 239#0102\n(12.010200) can3 5D9#AABBCCDD\nbad line\n".toByteArray()) } } }.apply { isDaemon = true }.start()
            CandumpStream("127.0.0.1", server.localPort, silenceMs = 2000).use { stream -> stream.run { t, bus, id, d, o, n -> frames += Frame(t, bus, id, d.copyOfRange(o, o + n)); true } }
        } finally {
            server.close()
        }
        assertEquals(listOf(12_000_100L to 0x239, 12_010_200L to 0x5D9), frames.map { it.time to it.id })
        assertTrue(frames.all { it.bus == 3 })
        assertArrayEquals(bytes("AABBCCDD"), frames[1].data)
    }

    @Test fun aCounterIsNotTakenForAMultiplexor() {
        val store = FrameStore()
        // byte 0: a 2-bit counter at bits 3-4; byte 1 changes with it, as data that differs per frame would
        for (i in 0 until 400) store.frame(i * 10_000L, 1, 0x344, byteArrayOf(((i and 3) shl 3).toByte(), (i * 37).toByte()), 0, 2)
        val found = AutoDecode.run(store, { null }, emptyList())
        assertTrue(found.none { it.signal.isMultiplexor })
        assertTrue(found.any { it.signal.name == "COUNTER_3" })
    }

    @Test fun diagnosticTrafficIsNotDecoded() {
        val store = FrameStore()
        // UDS on 0x7DF and an answer on 0x7E8: single and first/consecutive frames
        for (i in 0 until 200) {
            store.frame(i * 10_000L, 2, 0x7DF, byteArrayOf(0x02, 0x3E, (i % 3).toByte(), 0x55, 0x55, 0x55, 0x55, 0x55), 0, 8)
            store.frame(i * 10_000L + 5, 2, 0x7E8, byteArrayOf((0x10 + i % 3).toByte(), 0x0A, 0x62, (i * 7).toByte(), (i * 3).toByte(), 0, 0, 0), 0, 8)
        }
        assertTrue(AutoDecode.run(store, { null }, emptyList()).isEmpty())
    }

    @Test fun aKnownMultiplexorsPagesAreDecodedEachByItself() {
        // byte 0: page 0, 1, 0, 3 in turn; byte 1 is a level on page 0, a fast counter-like value on page 1, constant on page 3
        val store = FrameStore()
        for (i in 0 until 800) {
            val page = intArrayOf(0, 1, 0, 3)[i % 4]
            val data = when (page) { 0 -> (i / 40).toByte(); 1 -> (i * 7).toByte(); else -> 0x55 }
            store.frame(i * 10_000L, 1, 0x26D, byteArrayOf(page.toByte(), data), 0, 2)
        }
        val dbc = Dbc.parse("BO_ 621 UNK_26D: 2 XXX\n SG_ UNK_26D_index M : 0|2@1+ (1,0) [0|0] \"\" XXX\n", "d")
        val found = AutoDecode.run(store, { dbc.messages[it.id] }, emptyList())
        assertTrue(found.none { it.signal.isMultiplexor })
        assertTrue(found.any { it.signal.muxValue == 0 } && found.any { it.signal.muxValue == 1 } && found.none { it.signal.muxValue == 3 })
        val pipe = Dbc.parse("BO_ 473 VCSEC_IsoTpUDPPipeUI: 2 XXX\n SG_ a : 0|1@1+ (1,0) [0|0] \"\" XXX\n", "p")
        assertTrue(AutoDecode.run(store, { m -> pipe.messages[0x1D9].takeIf { m.id == 0x26D } }, emptyList()).isEmpty())
    }

    @Test fun rowsFollowTheStoreAndFindTimes() {
        val store = traceStore(1)
        val all = store.RowScan(null)
        val filtered = store.RowScan(Filter.compile("bus=1", store.messages) { null })
        assertEquals(110, all.update().size)
        var rows = filtered.update()
        assertEquals(10, rows.size)
        assertEquals(1_000, rows.time(1))
        assertEquals(5, rows.rowAt(5_500))
        assertEquals(-1, rows.rowAt(-1))
        assertEquals(9, rows.rowAt(Int.MAX_VALUE))
        val third = all.update().position(2)   // 0x118, 0x249, 0x118
        assertEquals(2, third and FrameStore.CAPACITY - 1)
        store.frame(2_000_000, 1, 0x249, bytes("29551001"), 0, 4)
        assertEquals(111, all.update().size)
        assertEquals(10, rows.size)   // rows handed out earlier don't change
        rows = filtered.update()
        assertEquals(11, rows.size)
        assertEquals(20_000, rows.time(10))
        store.clear()
        assertEquals(0, filtered.update().size)
        store.frame(0, 1, 0x249, bytes("00"), 0, 1)
        assertEquals(1, filtered.update().size)
    }

    @Test fun rowsStartOverWhenALiveStreamDropsItsOldestChunk() {
        val store = FrameStore(maxChunks = 2).apply { dropOldest = true }
        val odd = store.RowScan(Filter.compile("d0&01=1", emptyArray()) { null })
        val data = ByteArray(1)
        for (i in 0 until FrameStore.CAPACITY + 10) { data[0] = i.toByte(); store.frame(i * 100L, 0, 1, data, 0, 1) }
        assertEquals(FrameStore.CAPACITY / 2 + 5, odd.update().size)
        for (i in 0 until FrameStore.CAPACITY) { data[0] = 1; store.frame((FrameStore.CAPACITY + 10 + i) * 100L, 0, 1, data, 0, 1) }
        val rows = odd.update()
        assertEquals(store.chunks.sumOf { it.size }, rows.size + 5)   // of the 10 frames 0..9 left from before, 5 are even
        assertEquals(store.chunks[0].time[1], rows.time(0))
    }

    @Test fun changedBytesAndTheFixedView() {
        val store = FrameStore()
        store.frame(0, 0, 0x10, bytes("0102030405060708"), 0, 8)
        store.frame(1_000, 0, 0x10, bytes("0102FF0405060709"), 0, 8)
        store.frame(2_000, 0, 0x20, bytes("AA"), 0, 1)
        store.frame(3_000, 0, 0x10, bytes("0102FF04050607090A0B"), 0, 10)
        val chunk = store.chunks[0]
        assertEquals(listOf(0, 0x84, 0, 0x80), (0 until 4).map { chunk.changed[it].toInt() and 0xFF })
        assertEquals(0x80, store.messages[0].changed)
        var latest = store.latest(null)
        assertEquals(listOf(10, 1), latest.map { it.data.size })
        assertEquals(listOf(3L, 1L), latest.map { it.count })
        latest = store.latest(15)
        assertEquals(1, latest.size)
        assertEquals(0x84, latest[0].changed)
        assertEquals(2L, latest[0].count)
        assertEquals(10, latest[0].time)
        assertArrayEquals(bytes("0102FF0405060709"), latest[0].data)
        assertEquals(0, store.latest(-1).size)
        assertEquals(listOf(0x20), store.latest(null, Filter.compile("len=1", store.messages) { null }).map { it.message.id })
    }

    @Test fun theWifiLinkLogMetricsAndBluetoothStatus() {
        val status = DashKitStatus()
        status.read("G,I (1234) main: DashKit firmware ready ")
        status.read("M,12,40,38,27,1830,1200,0,3,1,0,2,-55,118")
        status.read("W,1,192.168.43.17")
        status.read("M,1")  // cut short: kept as it was
        status.read("G,I (2000) wifi:")
        status.read("g,state: init -> auth (0xb0) ")
        assertEquals(listOf("I (1234) main: DashKit firmware ready", "I (2000) wifi:state: init -> auth (0xb0)"), status.log())
        status.log().dropLast(1).let { assertEquals(listOf("I (1234) main: DashKit firmware ready"), it) }
        assertEquals(3L, status.logLines)
        assertEquals(listOf(12L, 40, 38, 27, 1830, 1200, 0, 3, 1, 0, 2, -55, 118), status.metrics!!.toList())
        assertTrue(status.wifiOn)
        assertEquals("192.168.43.17", status.wifiAddress)
    }

    @Test fun dashKitFramesKeepTheirBusTimes() {
        val times = ArrayList<Long>()
        val datas = ArrayList<String>()
        val sink = FrameSink { time, _, _, data, offset, length -> times += time; datas += (offset until offset + length).joinToString("") { "%02x".format(data[it]) }; true }
        val clock = DeviceClock()
        val buffer = ByteArray(64)
        // arrive together, came off the bus 1 ms apart; then the 32-bit clock wraps
        DashKitLine.parse("A,0,123,0102,fffffc18", 5_000_000, buffer, sink, clock)
        DashKitLine.parse("A,1,123,03,00000000", 5_000_000, buffer, sink, clock)
        DashKitLine.parse("A,1,456,04", 7_000_000, buffer, sink, clock)        // no time: the arrival
        DashKitLine.parse("A,1,456,05,000003e8", 6_000_000, buffer, sink)       // no clock: the arrival
        assertEquals(listOf(5_000_000L, 5_001_000L, 7_000_000L, 6_000_000L), times)
        assertEquals(listOf("0102", "03", "04", "05"), datas)
    }

    @Test fun transmitGates() {
        // A1, A2, A7: the bus it was seen on, 0 or 1; a DBC name, not a critical one; live and in Park
        assertNull(TxGuard.refusal(0, 0, "VCLEFT_doorStatus", live = true, park = true))
        assertTrue(TxGuard.refusal(0, 1, "VCLEFT_doorStatus", true, true)!!.contains("bus"))
        assertTrue(TxGuard.refusal(2, 2, "VCLEFT_doorStatus", true, true)!!.contains("bus"))
        assertTrue(TxGuard.refusal(0, 0, null, true, true)!!.contains("DBC"))
        assertTrue(TxGuard.refusal(0, 0, "DAS_control", true, true)!!.contains("steer"))
        assertTrue(TxGuard.refusal(0, 0, "VCLEFT_doorStatus", false, true)!!.contains("live"))
        assertTrue(TxGuard.refusal(0, 0, "VCLEFT_doorStatus", true, null)!!.contains("Park"))
        assertTrue(TxGuard.refusal(0, 0, "VCLEFT_doorStatus", true, false)!!.contains("Park"))
        for (name in listOf("DAS_control", "ACC_CONTROL", "LKAS11", "MDPS12", "SCC12", "TCS13", "HCA_01", "ACC_06", "GAS_COMMAND", "UI_cruiseState")) assertTrue(name, TxGuard.critical(name))
        // A4: what Park is
        for (name in listOf("P", "PARK", "DI_GEAR_P", "Park")) assertTrue(name, TxGuard.parkName(name))
        for (name in listOf("NOT_PARK", "EXIT_PARK", "REQ_P", "DI_GEAR_D", "PARKING_BRAKE")) assertFalse(name, TxGuard.parkName(name))
    }

    @Test fun parkNeedsEveryRecentGearSignalOnTheCarBuses() {
        val dbc = Dbc.parse(
            "BO_ 280 DI_systemStatus: 3 XXX\n SG_ DI_gear : 21|3@1+ (1,0) [0|0] \"\" XXX\n" +
                "BO_ 400 GEARBOX: 2 XXX\n SG_ page M : 0|4@1+ (1,0) [0|0] \"\" XXX\n SG_ gearShown m1 : 8|2@1+ (1,0) [0|0] \"\" XXX\n" +
                "VAL_ 280 DI_gear 1 \"DI_GEAR_P\" 4 \"DI_GEAR_D\" ;\nVAL_ 400 gearShown 0 \"P\" 1 \"D\" ;\n", "g")
        val decoder: (FrameStore.Message) -> DbcMessage? = { dbc.messages[it.id] }
        val store = FrameStore()
        store.frame(0, 0, 0x118, bytes("000020"), 0, 3)           // P
        assertEquals(true, TxGuard.inPark(store, decoder))
        store.frame(1000, 1, 0x190, bytes("0201"), 0, 2)           // page 2: gearShown isn't on it, so its "D" doesn't count
        assertEquals(true, TxGuard.inPark(store, decoder))
        store.frame(2000, 1, 0x190, bytes("0101"), 0, 2)           // page 1, D: the two disagree
        assertEquals(false, TxGuard.inPark(store, decoder))
        store.frame(3000, 1, 0x190, bytes("0100"), 0, 2)           // page 1, P
        assertEquals(true, TxGuard.inPark(store, decoder))
        store.frame(4000, 8, 0x118, bytes("000080"), 0, 3)        // openpilot's bus says D: not a car bus
        assertEquals(true, TxGuard.inPark(store, decoder))
        store.frame(900_000, 0, 0x700, bytes("00"), 0, 1)          // 0.9 s later, nothing new on gear: too old to tell
        assertNull(TxGuard.inPark(store, decoder))
    }

    @Test fun shortExtendedIdsStayApartFromStandardOnes() {
        val frames = ArrayList<Pair<Int, Int>>()
        val sink = FrameSink { _, bus, id, _, _, _ -> frames += bus to id; true }
        val buffer = ByteArray(64)
        LogReader.candump("(1.0) can0 123#00", buffer, sink)
        LogReader.candump("(1.0) can0 00000123#00", buffer, sink)
        LogReader.candump("(1.0) can0 18DAF110#00", buffer, sink)
        DashKitLine.parse("A,1,00000123,00", 0, buffer, sink)
        assertEquals(listOf(0 to 0x123, 0 to (0x123 or CanId.EXTENDED), 0 to 0x18DAF110, 1 to (0x123 or CanId.EXTENDED)), frames)
        val text = StringBuilder().apply { appendCandump(1_000_000, 0, 0x123 or CanId.EXTENDED, buffer, 0, 1); appendCandump(1_000_000, 0, 0x123, buffer, 0, 1) }.toString()
        assertEquals("(1.000000) can0 00000123#00\n(1.000000) can0 123#00\n", text)
        val dbc = Dbc.parse("BO_ 2147483939 SHORT_EXT: 1 X\n SG_ s : 0|8@1+ (1,0) [0|0] \"\" X\n\nBO_ 291 STD: 1 X\n SG_ t : 0|8@1+ (1,0) [0|0] \"\" X\n", "x")
        assertEquals("SHORT_EXT", dbc.messages[0x123 or CanId.EXTENDED]?.name)
        assertEquals("STD", dbc.messages[0x123]?.name)
        assertTrue(dbc.write().contains("BO_ 2147483939 SHORT_EXT"))
        assertNull(DashKitLine.transmit(0, 0x123 or CanId.EXTENDED, bytes("00")))
    }

    @Test fun wideSignedAndOneBitSignals() {
        val d = bytes("FFFFFFFFFFFFFFFF")
        val wide = Signal("w", 0, 64, true, false, 1.0, 0.0, "", -1, false)
        assertEquals(-1L, wide.raw(d))
        assertEquals(1.8446744073709552E19, wide.physical(wide.raw(d)), 1e4)          // read as unsigned
        val signedBig = Signal("s", 7, 12, false, true, 1.0, 0.0, "", -1, false)     // big-endian: byte 0 and the top half of byte 1
        assertEquals(-1L, signedBig.raw(bytes("FFF0")))
        assertEquals(0x7FFL, signedBig.raw(bytes("7FF0")))
        assertEquals(-2048L, signedBig.raw(bytes("8000")))
        val bit = Signal("b", 13, 1, true, false, 1.0, 0.0, "", -1, false)
        assertEquals(1L, bit.raw(bytes("0020")))
        assertEquals(0L, bit.raw(bytes("FFDF")))
    }

    @Test fun csvWithQuotesCrlfAndExtendedIds() {
        val (format, frames) = read("Time Stamp,ID,Extended,Dir,Bus,LEN,D1,D2\r\n\"1000\",\"0x123\",\"true\",Rx,0,2,01,02\r\n2000,456,false,Rx,1,1,FF\r\n")
        assertEquals("CSV", format)
        assertEquals(listOf(0x123 or CanId.EXTENDED, 0x456), frames.map { it.id })
        assertArrayEquals(bytes("0102"), frames[0].data)
        assertEquals(listOf(1000L, 2000L), frames.map { it.time })
    }

    @Test fun ascInDecimalAndCanFdLinesSkipped() {
        val (format, frames) = read("base dec  timestamps absolute\n   0.010000 1  291  Rx   d 2 1 255\n   0.020000 CANFD 1 Rx 123 1 0 8 8 01 02 03 04 05 06 07 08\n")
        assertEquals("ASC", format)
        assertEquals(listOf(291), frames.map { it.id })
        assertArrayEquals(bytes("01FF"), frames[0].data)
    }

    @Test fun filterEdgeCases() {
        assertNull(Filter.compile("", emptyArray()) { null })
        assertNull(Filter.compile("   ", emptyArray()) { null })
        // each mistake is named, not passed over
        for ((text, said) in listOf("(id=1" to ")", "id=" to "id=", "id=zz" to "zz", ")" to ")")) {
            val e = runCatching { Filter.compile(text, emptyArray()) { null } }.exceptionOrNull()
            assertTrue(text, e is IllegalArgumentException && said in e.message.orEmpty())
        }
    }

    @Test fun deviceClockKeepsALateFrameFromBeforeAWrapInItsLap() {
        val clock = DeviceClock()
        assertEquals(1_000L, clock.micros(0xFFFFFF00L, 1_000))
        assertEquals(1_000L + 0x100 + 0x10, clock.micros(0x10L, 2_000))               // wrapped
        assertEquals(1_000L + 0x80, clock.micros(0xFFFFFF80L, 3_000))                  // late, from before the wrap
        assertEquals(1_000L + 0x100 + 0x20, clock.micros(0x20L, 4_000))                // and on again
    }

    @Test fun dashKitLinesWithImpossibleIdsAreSkipped() {
        val frames = ArrayList<Int>()
        val sink = FrameSink { _, _, id, _, _, _ -> frames += id; true }
        val buffer = ByteArray(64)
        DashKitLine.parse("A,0,FFFFFFFFF,00", 0, buffer, sink)
        DashKitLine.parse("A,0,3FFFFFFF,00", 0, buffer, sink)
        DashKitLine.parse("A,0,1FFFFFFF,00", 0, buffer, sink)
        DashKitLine.parse("A,0,zz,00", 0, buffer, sink)
        assertEquals(listOf(0x1FFFFFFF), frames)
    }

    @Test fun packedWifiFramesComeOutWhole() {
        fun le(v: Long, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }
        val datagram = byteArrayOf('B'.code.toByte()) + le(41, 4) + le(0xFFFFFF00, 4) +
            byteArrayOf(0x02) + le(0, 2) + le(0x123, 2) + bytes("AABB") +                      // bus 0, std, 2 bytes
            byteArrayOf((0x80 or 0x40 or 0x01).toByte()) + le(0x200, 2) + le(0x18DAF110, 4) + bytes("CC")  // bus 1, ext, after the wrap
        val frames = ArrayList<Frame>()
        val sink = FrameSink { t, bus, id, d, o, n -> frames += Frame(t, bus, id, d.copyOfRange(o, o + n)); true }
        assertEquals(41L, DashKitLine.parsePacked(datagram, datagram.size, 5_000_000, sink, DeviceClock()))
        assertEquals(listOf(0 to 0x123, 1 to 0x18DAF110), frames.map { it.bus to it.id })
        assertEquals(listOf(5_000_000L, 5_000_512L), frames.map { it.time })
        assertArrayEquals(bytes("CC"), frames[1].data)
        assertEquals(-1L, DashKitLine.parsePacked(datagram, datagram.size - 1, 0, sink, DeviceClock()))   // cut short
        assertEquals(-1L, DashKitLine.parsePacked("Q,1\n".toByteArray(), 4, 0, sink, DeviceClock()))
    }

    @Test fun compactWifiFramesComeOutWhole() {
        fun le(v: Long, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }
        val datagram = byteArrayOf('C'.code.toByte()) + le(7, 4) + le(0xFFFFFF00, 4) +
            le(2L shl 11 or 0x123, 2) + byteArrayOf(0) + bytes("AABB") +                                    // bus 0, 2 bytes, at the start
            le(1L shl 15 or (8L shl 11) or 0x7FF, 2) + byteArrayOf(200.toByte()) + bytes("0102030405060708") + // bus 1, 200 µs later
            le(15L shl 11, 2) + byteArrayOf(255.toByte()) + le(1000, 2) + byteArrayOf(1) + le(0x18DAF110, 4) + bytes("CC")  // 1000 µs later, extended
        val frames = ArrayList<Frame>()
        val sink = FrameSink { t, bus, id, d, o, n -> frames += Frame(t, bus, id, d.copyOfRange(o, o + n)); true }
        assertEquals(7L, DashKitLine.parsePacked(datagram, datagram.size, 5_000_000, sink, DeviceClock()))
        assertEquals(listOf(0 to 0x123, 1 to 0x7FF, 0 to 0x18DAF110), frames.map { it.bus to it.id })
        assertEquals(listOf(5_000_000L, 5_000_200L, 5_001_200L), frames.map { it.time })
        assertArrayEquals(bytes("0102030405060708"), frames[1].data)
        assertArrayEquals(bytes("CC"), frames[2].data)
        for (cut in 10 until datagram.size) assertTrue(DashKitLine.parsePacked(datagram, cut, 0, FrameSink { _, _, _, _, _, _ -> true }, DeviceClock()) <= 7)
        assertEquals(-1L, DashKitLine.parsePacked(datagram, datagram.size - 1, 0, sink, DeviceClock()))   // cut short
    }

    @Test fun framesTheDashKitSentComeOnBus128Up() {
        fun le(v: Long, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }
        val datagram = byteArrayOf('C'.code.toByte()) + le(1, 4) + le(100, 4) +
            le(1L shl 15 or (14L shl 11) or 0x2F3, 2) + byteArrayOf(0, 2) + bytes("8909") +                           // sent on bus 1
            le(14L shl 11, 2) + byteArrayOf(5, 0x41) + le(0x18DAF110, 4) + bytes("AA")                                // sent, extended
        val frames = ArrayList<Frame>()
        val sink = FrameSink { t, bus, id, d, o, n -> frames += Frame(t, bus, id, d.copyOfRange(o, o + n)); true }
        assertEquals(1L, DashKitLine.parsePacked(datagram, datagram.size, 0, sink, DeviceClock()))
        assertEquals(listOf(129 to 0x2F3, 128 to 0x18DAF110), frames.map { it.bus to it.id })
        assertArrayEquals(bytes("8909"), frames[0].data)
        frames.clear()
        assertTrue(DashKitLine.parse("E,0,334,bf3f1408ff0152a3", 7, ByteArray(8), sink))
        assertEquals(listOf(128 to 0x334), frames.map { it.bus to it.id })
    }

    @Test fun commaStatsAndLogAreRead() {
        // made with pycapnp from openpilot's log.capnp: deviceState, pandaStates, managerState, errorLogMessage
        val events = listOf("0000000020000000000000000200010001000000000000000500000000000000000000000f000b00000000000000000000000000000000000000a04200000000002a010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000d0400000000000005c42000000000000000000000000000000000000000000000000000000000000000029000000140000000000000000000000000000000000000019000000120000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000c220000000000000000484200004c42", "000000001b000000000000000200010000000000000000004f000000000000000100000077000000040000000a000400000000000000000002000000000000000000000000000000000000000000000000000000030000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000040000000800000000000000000000000409000000000000000000000000070005000000000000000100000014000000e803000000000000000000000000000000000000000000000000000000000000", "0000000010000000000000000200010000000000000000004c00000000000000000000000000010001000000370000000800000002000100000000000300000000000000000000000d000000520000000000000002000000f5ffffff00000000090000004a000000636f6e74726f6c736400000000000000646173686b6974640000000000000000", "0000000006000000000000000200010000000000000000005300000000000000010000007a0000007b226d7367223a22626f6f6d227d0000")
        val status = CommaStatus()
        val said = ArrayList<String>()
        status.onLine = { said += it }
        for (hex in events) {
            val m = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            val segments = Cereal.int(m, 0) + 1
            val starts = IntArray(segments + 1)
            var at = (4 + 4 * segments + 7) / 8 * 8
            for (s in 0 until segments) { starts[s] = at; at += Cereal.int(m, 4 + 4 * s) * 8 }
            starts[segments] = at
            assertTrue(status.read(m, starts, segments))
        }
        assertEquals("CPU % 12 34 · temp °C 50 51 (max 55) · memory 42% · storage free 80% · 6.5 W · thermal 1", status.device)
        assertTrue(status.buses, status.buses.contains("panda 0 bus 1: rx 1000 tx 20 lost rx 1 tx 0 · errors 5 (TEC 7 REC 9) passive"))
        assertTrue(status.buses, status.buses.contains("panda 0: controls allowed · rx invalid 3 · tx blocked 0"))
        assertEquals("not running: dashkitd (exit -11)", status.processes)
        assertEquals(listOf("ERROR {\"msg\":\"boom\"}"), status.log())
        assertEquals(4, said.size)
    }
}
