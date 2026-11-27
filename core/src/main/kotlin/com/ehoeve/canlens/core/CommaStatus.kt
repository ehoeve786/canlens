package com.ehoeve.canlens.core

/**
 * What a comma says about itself through its cereal bridge: `deviceState` (CPU, temperatures, memory), `pandaStates`
 * (the CAN health of each bus), `managerState` (processes that should run and don't) and its log (`logMessage`,
 * `errorLogMessage`). Written by the reader threads, read by the UI; each summary is replaced whole.
 * Field positions are openpilot's log.capnp (fields there are only ever added, so they hold for forks such as IQ.Pilot).
 */
class CommaStatus {
    @Volatile var device = ""; private set
    @Volatile var buses = ""; private set
    @Volatile var processes = ""; private set
    private class Entry(val line: String, var count: Int)
    private val log = ArrayDeque<Entry>()
    @Volatile var logLines = 0L; private set
    fun log(): List<String> = synchronized(log) { log.map { if (it.count > 1) "${it.line} (×${it.count})" else it.line } }
    /** Called with each new summary or log line, as it would be recorded (no newline). */
    @Volatile var onLine: ((String) -> Unit)? = null

    /** One Event message from any of the services; false never (the bridge's other events are just skipped). */
    fun read(m: ByteArray, starts: IntArray, segments: Int): Boolean {
        try { Reader(m, starts, segments).event() } catch (e: IndexOutOfBoundsException) { /* a pointer outside the message */ }
        return true
    }

    private fun add(line: String) {
        // a line seen again among the last few (iquploaderd and raylib take turns every second) counts up and moves to the end
        synchronized(log) {
            val i = (log.size - 1 downTo maxOf(0, log.size - RECENT)).firstOrNull { log[it].line == line }
            log.addLast(i?.let { log.removeAt(it).apply { count++ } } ?: Entry(line, 1))
            while (log.size > LOG_LINES) log.removeFirst()
        }
        logLines++
        onLine?.invoke("log $line")
    }

    private var pandasAt = 0L

    private inner class Reader(val m: ByteArray, val starts: IntArray, val segments: Int) {
        val end = starts[segments]
        fun word(at: Int): Long { if (at < 0 || at + 8 > end) throw IndexOutOfBoundsException(); return Cereal.long(m, at) }

        /** A struct the pointer at [at] points to: its data and pointer sections; null if none. */
        inner class Struct(val data: Int, val words: Int, val pointers: Int, val count: Int) {
            fun u8(byte: Int) = if (byte < words * 8) (word(data + byte / 8 * 8) ushr (byte % 8 * 8) and 0xFF).toInt() else 0
            fun u16(at: Int) = u8(at * 2) or (u8(at * 2 + 1) shl 8)
            fun u32(at: Int) = (u16(at * 2).toLong() or (u16(at * 2 + 1).toLong() shl 16))
            fun f32(at: Int) = Float.fromBits(u32(at).toInt())
            fun bit(at: Int) = u8(at / 8) ushr (at % 8) and 1 == 1
            fun pointer(i: Int) = if (i < count) pointers + i * 8 else -1
        }

        fun struct(at: Int): Struct? {
            if (at < 0) return null
            val p = Cereal.follow(m, starts, segments, at, end).takeIf { it >= 0 } ?: return null
            val w = word(p)
            if (w and 3L != 0L) return null
            val data = p + 8 + (w.toInt() shr 2) * 8
            val words = (w ushr 32 and 0xFFFF).toInt()
            return Struct(data, words, data + words * 8, (w ushr 48 and 0xFFFF).toInt())
        }

        /** A list at the pointer [at]: (first element, count, element bytes) for plain lists, structs for composite ones. */
        fun list(at: Int): Triple<Int, Int, Int>? {
            if (at < 0) return null
            val p = Cereal.follow(m, starts, segments, at, end).takeIf { it >= 0 } ?: return null
            val w = word(p)
            if (w and 3L != 1L) return null
            val start = p + 8 + (w.toInt() shr 2) * 8
            val count = (w ushr 35).toInt()
            return when ((w ushr 32 and 7).toInt()) {
                2 -> Triple(start, count, 1)
                4 -> Triple(start, count, 4)
                else -> null
            }
        }

        fun structs(at: Int): List<Struct> {
            if (at < 0) return emptyList()
            val p = Cereal.follow(m, starts, segments, at, end).takeIf { it >= 0 } ?: return emptyList()
            val w = word(p)
            if (w and 3L != 1L || (w ushr 32 and 7).toInt() != 7) return emptyList()
            val tag = p + 8 + (w.toInt() shr 2) * 8
            val t = word(tag)
            val words = (t ushr 32 and 0xFFFF).toInt()
            val count = (t ushr 48 and 0xFFFF).toInt()
            return List(minOf((t.toInt() ushr 2), 256)) { Struct(tag + 8 + it * (words + count) * 8, words, tag + 8 + it * (words + count) * 8 + words * 8, count) }
        }

        fun text(at: Int): String {
            val (start, count, size) = list(at) ?: return ""
            if (size != 1 || count < 1 || start + count > end) return ""
            return String(m, start, count - 1, Charsets.UTF_8)
        }

        fun event() {
            val e = struct(starts[0]) ?: return
            val which = e.u16(4)
            val body = e.pointer(0)
            when (which) {
                DEVICE_STATE -> struct(body)?.let { d ->
                    val cpu = list(d.pointer(4))?.let { (s, n, z) -> if (z == 1) (0 until n).joinToString(" ") { "${m[s + it]}" } else "" }.orEmpty()
                    val temps = list(d.pointer(1))?.let { (s, n, z) -> if (z == 4) (0 until n).joinToString(" ") { "%.0f".format(Float.fromBits(Cereal.int(m, s + it * 4))) } else "" }.orEmpty()
                    device = "CPU %% $cpu · temp °C $temps (max %.0f) · memory ${d.u8(25)}%% · storage free %.0f%% · %.1f W · thermal ${d.u16(13)}"
                        .format(d.f32(23), d.f32(4), d.f32(21))
                    onLine?.invoke("device $device")
                }
                PANDA_STATES -> {
                    val now = System.nanoTime()
                    if (now - pandasAt < 1_000_000_000L) return   // 10 a second: one is enough
                    pandasAt = now
                    buses = structs(body).flatMapIndexed { panda, p ->
                        (0..2).mapNotNull { bus ->
                            struct(p.pointer(1 + bus))?.let { c ->
                                "panda $panda bus $bus: rx ${c.u32(8)} tx ${c.u32(7)} lost rx ${c.u32(6)} tx ${c.u32(5)} · errors ${c.u32(4)} (TEC ${c.u8(14)} REC ${c.u8(1)})" +
                                    (if (c.bit(0)) " BUS OFF" else if (c.bit(2)) " passive" else if (c.bit(1)) " warning" else "")
                            }
                        } + listOf("panda $panda: controls ${if (p.bit(65)) "allowed" else "not allowed"} · rx invalid ${p.u32(9)} · tx blocked ${p.u32(12)}")
                    }.joinToString("\n")
                    onLine?.invoke("pandas " + buses.replace('\n', ';'))
                }
                MANAGER_STATE -> {
                    val down = structs(e.pointer(0).let { struct(it)?.pointer(0) ?: -1 }).filter { it.bit(33) && !it.bit(32) }
                        .map { "${text(it.pointer(0))} (exit ${it.u32(2).toInt()})" }
                    val now = if (down.isEmpty()) "all running" else "not running: " + down.joinToString(", ")
                    if (now != processes) { processes = now; onLine?.invoke("processes $now") }
                }
                LOG_MESSAGE, ERROR_LOG_MESSAGE -> text(body).takeIf { it.isNotEmpty() }?.let { logLine(it, which == ERROR_LOG_MESSAGE) }?.let(::add)
            }
        }
    }

    companion object {
        /** The services, each on its own bridge port ([CommaStream.cerealPort]). */
        val SERVICES = listOf("deviceState", "pandaStates", "managerState", "logMessage", "errorLogMessage")
        const val LOG_LINES = 500
        /** How many of the latest lines a repeat is looked for among. */
        private const val RECENT = 4
        private const val DEVICE_STATE = 5
        private const val LOG_MESSAGE = 17
        private const val MANAGER_STATE = 76
        private const val PANDA_STATES = 79
        private const val ERROR_LOG_MESSAGE = 83
        /** A cloudlog JSON line (Python's or C++'s) as "LEVEL file: msg", colour codes gone; null for DEBUG (the manager's process list, loggerd's counts). */
        fun logLine(line: String, error: Boolean): String? {
            val level = LEVEL.find(line)?.groupValues?.get(1)
                ?: LEVEL_NUM.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { listOf("DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL").getOrNull(it / 10 - 1) }
                ?: if (error) "ERROR" else ""
            if (level == "DEBUG") return null
            val msg = MSG_TEXT.find(line)?.groupValues?.get(1)?.replace("\\\"", "\"") ?: MSG_OBJECT.find(line)?.groupValues?.get(1) ?: line
            val file = FILE.find(line)?.groupValues?.get(1)?.substringAfterLast('/')?.substringBeforeLast('.')?.let { "$it:" }.orEmpty()
            return listOf(level, file, msg).filter { it.isNotEmpty() }.joinToString(" ").replace(ANSI, "")
        }

        private val LEVEL = Regex("\"level\": \"(\\w+)\"")
        private val LEVEL_NUM = Regex("\"levelnum\": (\\d+)")
        private val MSG_TEXT = Regex("\"msg\": \"((?:[^\"\\\\]|\\\\.)*)\"")
        private val MSG_OBJECT = Regex("\"msg\": (\\{.*?\\}), \"ctx\"")
        private val FILE = Regex("\"filename\": \"([^\"]+)\"")
        private val ANSI = Regex("(\\\\u001b|\u001b)\\[[0-9;]*m")
    }
}
