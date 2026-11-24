package com.ehoeve.canpilot.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals(-1, m.multiplexor!!.muxValue)
    }

    @Test fun dbcSurvivesWritingAndReading() {
        val added = dbc.withSignal(0x123, "NEW_123", 8, Signal("guess", 7, 10, false, false, 0.1, 5.0, "V", -1, false))
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
        while (store.frame(1000, 0, 1, data, 0, 8)) accepted++
        assertTrue(store.truncated)
        assertEquals(FrameStore.CAPACITY - 3, accepted)
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
        assertEquals((0..15).map { it.toString() }, histogram.map { it.first })
        assertEquals(600, histogram.sumOf { it.second })
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
}
