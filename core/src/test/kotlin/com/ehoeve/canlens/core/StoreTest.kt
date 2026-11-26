package com.ehoeve.canlens.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

class StoreTest {
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun read(bytes: ByteArray): Pair<String, List<Pair<Long, ByteArray>>> {
        val frames = ArrayList<Pair<Long, ByteArray>>()
        val format = LogReader.read(bytes.inputStream(), { t, _, _, d, o, n -> frames += t to d.copyOfRange(o, o + n); true })
        return format to frames
    }

    @Test fun liveFramesThatArriveOutOfOrderKeepTheStoreInTimeOrder() {
        val live = FrameStore().apply { dropOldest = true }
        // the comma's batch stamp, then a candump line stamped before it, then the next batch
        for (micros in listOf(0L, 10_000L, 10_000L, 4_000L, 20_000L, 15_000L)) live.frame(micros, 0, 0x100, bytes("01"), 0, 1)
        val times = (0 until live.chunks[0].size).map { live.chunks[0].time[it] }
        assertEquals(listOf(0, 100, 100, 100, 200, 200), times)
        assertEquals(200, live.lastTime)
        val rows = live.RowScan(null).update()
        assertEquals(3, rows.rowAt(100))
        assertEquals(6L, live.latest(250).single().count)
        // a log keeps its times as written
        val log = FrameStore()
        log.frame(10_000L, 0, 0x100, bytes("01"), 0, 1)
        log.frame(4_000L, 0, 0x100, bytes("01"), 0, 1)
        assertEquals(-60, log.chunks[0].time[1])
    }

    @Test fun timesOutsideTheStoresRangeTruncateIt() {
        val store = FrameStore()
        assertTrue(store.frame(0, 0, 1, bytes("00"), 0, 1))
        assertFalse(store.frame(300L * 3600 * 1_000_000, 0, 1, bytes("00"), 0, 1))
        assertTrue(store.truncated)
        assertEquals(1L, store.frameCount)
    }

    @Test fun aMergeTakesNoMoreThanTheStoresBudget() {
        val store = FrameStore(maxChunks = 1)
        store.frame(0, 0, 1, bytes("00"), 0, 1)
        assertEquals(1, store.spareChunks)
        val other = FrameStore(store.spareChunks)
        val data = ByteArray(1)
        var time = 1L
        while (other.frame(time++, 0, 2, data, 0, 1)) Unit
        assertEquals(FrameStore.CAPACITY.toLong(), other.frameCount)
        store.merge(other, 0, 10)
        assertEquals(1, store.chunks.size)
        assertTrue(store.truncated)
        assertEquals(FrameStore.CAPACITY.toLong(), store.frameCount)
        assertEquals(2, store.messages.size)
    }

    @Test fun scaledSignalsEqualTheDecimalWritten() {
        val dbc = Dbc.parse("BO_ 256 M: 1 XXX\n SG_ level : 0|8@1+ (0.1,0) [0|0] \"\" XXX\n", "f")
        val store = FrameStore()
        store.frame(0, 0, 0x100, byteArrayOf(3), 0, 1)   // 3 x 0.1 = 0.30000000000000004
        fun count(text: String) = store.RowScan(Filter.compile(text, store.messages) { dbc.messages[it.id] }).update().size
        assertEquals(1, count("level=0.3"))
        assertEquals(1, count("level=0.33"))   // the nearest step
        assertEquals(0, count("level=0.4"))
        assertEquals(0, count("level!=0.3"))
        assertEquals(1, count("level<=0.3"))
        assertEquals(0, count("level<0.3"))
        assertEquals(1, count("level>=0.3"))
        assertEquals(0, count("level>0.3"))
        assertEquals(1, count("level=0.3-0.5"))
        assertEquals(0, count("level=0.4-0.5"))
        assertEquals(1, count("level<0.35"))
    }

    @Test fun aCsvWithAByteOrderMarkIsText() {
        val csv = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "time,addr,bus,data\n1.5,0x118,0,0102\n".toByteArray()
        val (format, frames) = read(csv)
        assertEquals("CSV", format)
        assertEquals(1_500_000L, frames.single().first)
        // a name in UTF-8 does not make it binary either
        val (again, more) = read("# Fahrt über die Brücke\n(1.000000) can0 118#01\n".toByteArray())
        assertEquals("candump" to 1, again to more.size)
    }

    @Test fun overlongLinesAreSkipped() {
        val (format, frames) = read(("(1.0) can0 118#" + "00".repeat(5000) + "\n(2.0) can0 118#01\n").toByteArray())
        assertEquals("candump", format)
        assertEquals(listOf(2_000_000L), frames.map { it.first })
    }

    @Test fun negativeSecondsKeepTheirSign() {
        assertEquals("-1.500000", StringBuilder().appendSeconds(-1_500_000).toString())
        assertEquals("-0.000500", StringBuilder().appendSeconds(-500).toString())
        assertEquals("1.500000", StringBuilder().appendSeconds(1_500_000).toString())
    }

    @Test fun canFdFramesSurviveWritingAndReading() {
        val fd = ByteArray(12) { (it * 17).toByte() }
        val written = StringBuilder().apply { appendCandump(2_000_000, 1, 0x18DAF110, fd, 0, fd.size); appendCandump(3_000_000, 1, 0x118, fd, 0, 8) }.toString()
        assertEquals("(2.000000) can1 18DAF110##0" + fd.joinToString("") { "%02X".format(it) }, written.lines()[0])
        assertTrue(written.lines()[1].endsWith(" 118#0011223344556677"))
        val (_, frames) = read(written.toByteArray())
        assertArrayEquals(fd, frames[0].second)
        assertArrayEquals(fd.copyOf(8), frames[1].second)
    }

    /** A server that takes one connection and hands it to [talk]. */
    private fun server(talk: (java.net.Socket) -> Unit) = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also { server ->
        Thread { runCatching { server.accept().use(talk) } }.apply { isDaemon = true }.start()
    }

    private val greeting = ByteArray(64).also { it[0] = 0xFF.toByte(); it[9] = 0x7F; it[10] = 3 }

    @Test fun aCommaThatGoesSilentIsDropped() {
        val server = server { socket ->
            DataInputStream(socket.getInputStream()).readFully(ByteArray(64))
            socket.getOutputStream().write(greeting)
            Thread.sleep(5000)
        }
        val started = System.nanoTime()
        val e = assertThrows(IOException::class.java) { CommaStream("127.0.0.1", server.localPort, silenceMs = 300).use { it.run { _, _, _, _, _, _ -> true } } }
        assertTrue(e.message!!, "sent nothing" in e.message!!)
        assertTrue(System.nanoTime() - started < 3_000_000_000L)
        server.close()
    }

    @Test fun aZeroMqErrorEndsTheStream() {
        val error = byteArrayOf(5) + "ERROR".toByteArray() + byteArrayOf(6) + "denied".toByteArray()
        val server = server { socket ->
            DataInputStream(socket.getInputStream()).readFully(ByteArray(64))
            socket.getOutputStream().write(greeting + byteArrayOf(4, error.size.toByte()) + error)
            Thread.sleep(2000)
        }
        val e = assertThrows(IOException::class.java) { CommaStream("127.0.0.1", server.localPort, silenceMs = 1500).use { it.run { _, _, _, _, _, _ -> true } } }
        assertTrue(e.message!!, "denied" in e.message!!)
        server.close()
    }

    @Test fun aSilentCandumpLinkIsDropped() {
        val server = server { socket ->
            socket.getOutputStream().write("(1.0) can3 118#01\n".toByteArray())
            Thread.sleep(5000)
        }
        var frames = 0
        val e = assertThrows(IOException::class.java) { CandumpStream("127.0.0.1", server.localPort, silenceMs = 300).use { it.run { _, _, _, _, _, _ -> frames++; true } } }
        assertTrue(e.message!!, "sent nothing" in e.message!!)
        assertEquals(1, frames)
        server.close()
    }
}
