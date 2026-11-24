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
        val format = LogReader.read(bytes.inputStream()) { t, bus, id, d, o, n -> frames += Frame(t, bus, id, d.copyOfRange(o, o + n)); true }
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
}
