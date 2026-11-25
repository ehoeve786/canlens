package com.ehoeve.canlens.core

/**
 * A condition on frames, written as text:
 *
 *     bus=1 id=249            terms side by side (commas are fine too) must all hold; `or`, `not` and brackets combine them
 *     id=100-1FF len>4        ids and data bytes are hexadecimal, a-b is a range
 *     d0=FF d2&0F!=3          byte 0 is FF, the low nibble of byte 2 is not 3
 *     t=73.5-75               seconds since the start
 *     DAS_lssState=FAULT      a DBC signal against a number or one of its value names
 *     changed                 the payload differs from the message's previous frame
 *     stalk                   a bare word: part of the id, the message's name or one of its signals' names
 */
class Filter private constructor(private val parts: Array<Node>, /** The text of each of the terms that must all hold. */ val terms: List<String>) {

    fun test(message: FrameStore.Message, time: Int, data: ByteArray, start: Int, length: Int, changed: Int): Boolean {
        for (part in parts) if (!part.test(message, time, data, start, length, changed)) return false
        return true
    }

    /** Whether the frame meets term [term] alone. */
    internal fun test(term: Int, message: FrameStore.Message, time: Int, data: ByteArray, start: Int, length: Int, changed: Int) =
        parts[term].test(message, time, data, start, length, changed)

    private fun interface Node {
        fun test(message: FrameStore.Message, time: Int, data: ByteArray, start: Int, length: Int, changed: Int): Boolean
    }

    /** A comparison with a number or a range; kept as a class so that testing a frame allocates nothing. */
    private class Check(operator: String, private val low: Double, private val high: Double) {
        private val kind = COMPARISONS.indexOf(operator)   // -1 for the ways of writing "equals"
        fun test(value: Double) = when (kind) {
            0 -> value < low || value > high
            1 -> value < low
            2 -> value <= low
            3 -> value > low
            4 -> value >= low
            else -> value >= low && value <= high
        }
    }

    private class SignalTest(val signal: Signal, val multiplexor: Signal?, val check: Check, val onRaw: Boolean)

    private class Parser(private val tokens: List<String>, private val messages: Array<FrameStore.Message>, private val decoder: (FrameStore.Message) -> DbcMessage?) {
        private var at = 0
        private val next: String? get() = tokens.getOrNull(at)

        fun all(): Node = either().also { if (at < tokens.size) fail("Unexpected ${tokens[at]}") }

        private fun either(): Node {
            var left = both()
            while (next in OR) {
                at++
                val a = left
                val b = both()
                left = Node { message, time, data, start, length, changed -> a.test(message, time, data, start, length, changed) || b.test(message, time, data, start, length, changed) }
            }
            return left
        }

        private fun both(): Node {
            var left = negation()
            while (at < tokens.size && next != ")" && next !in OR) {
                if (next in AND) at++
                val a = left
                val b = negation()
                left = Node { message, time, data, start, length, changed -> a.test(message, time, data, start, length, changed) && b.test(message, time, data, start, length, changed) }
            }
            return left
        }

        private fun negation(): Node {
            if (next !in NOT) return primary()
            at++
            val inner = negation()
            return Node { message, time, data, start, length, changed -> !inner.test(message, time, data, start, length, changed) }
        }

        private fun primary(): Node {
            val token = next ?: fail("Something is missing at the end")
            at++
            if (token == ")" || token in OR || token in AND) fail("Unexpected $token")
            if (token != "(") return term(token)
            val inner = either()
            if (next != ")") fail("A ) is missing")
            at++
            return inner
        }

        private fun term(token: String): Node {
            val parts = TERM.matchEntire(token)
                ?: return if (token.any { it in "=<>:&" }) fail("Can't read \"$token\". Write it like bus=1, id=249 or d0&0F=3.") else word(token)
            val (field, mask, operator, value) = parts.destructured
            val name = field.lowercase()
            val byte = BYTE.matchEntire(name)?.groupValues?.get(1)?.toInt()
            if (mask.isNotEmpty() && byte == null) fail("& only works on data bytes, as in d0&0F=3")
            return when {
                byte != null -> {
                    val check = check(operator, value, ::hex)
                    val keep = if (mask.isEmpty()) 0xFF else mask.toInt(16)
                    Node { _, _, data, start, length, _ -> byte < length && check.test((data[start + byte].toInt() and keep).toDouble()) }
                }
                name == "bus" -> check(operator, value, String::toDoubleOrNull).let { check -> Node { message, _, _, _, _, _ -> check.test(message.bus.toDouble()) } }
                name == "id" -> check(operator, value, ::hex).let { check -> Node { message, _, _, _, _, _ -> check.test(message.id.toDouble()) } }
                name == "len" -> check(operator, value, String::toDoubleOrNull).let { check -> Node { _, _, _, _, length, _ -> check.test(length.toDouble()) } }
                name == "t" -> check(operator, value, String::toDoubleOrNull).let { check -> Node { _, time, _, _, _, _ -> check.test(time / FrameStore.TICKS_PER_SECOND) } }
                else -> signal(field, operator, value)
            }
        }

        private fun signal(name: String, operator: String, value: String): Node {
            val tests = arrayOfNulls<SignalTest>(messages.size)
            var found = false
            for (message in messages) {
                val dbcMessage = decoder(message) ?: continue
                val signal = dbcMessage.signals.firstOrNull { it.name.equals(name, true) } ?: continue
                found = true
                val named = signal.valueNames.entries.firstOrNull { it.value.equals(value, true) }?.key
                val check = if (named != null) check(operator, named.toString(), String::toDoubleOrNull) else check(operator, value, String::toDoubleOrNull)
                tests[message.index] = SignalTest(signal, if (signal.muxValue >= 0) dbcMessage.multiplexor else null, check, named != null)
            }
            if (!found) fail("No signal named $name in this data. Fields are bus, id, len, t and d0 to d63.")
            return Node { message, _, data, start, length, _ ->
                val test = tests.getOrNull(message.index)
                if (test == null || !test.signal.fits(length)) return@Node false
                val multiplexor = test.multiplexor
                if (multiplexor != null && (!multiplexor.fits(length) || multiplexor.raw(data, start) != test.signal.muxValue.toLong())) return@Node false
                val raw = test.signal.raw(data, start)
                test.check.test(if (test.onRaw) raw.toDouble() else test.signal.physical(raw))
            }
        }

        private fun word(token: String): Node {
            if (token.equals("changed", true)) return Node { _, _, _, _, _, changed -> changed != 0 }
            val needle = token.lowercase()
            val matches = BooleanArray(messages.size) { index ->
                val message = messages[index]
                val dbcMessage = decoder(message)
                needle in Integer.toHexString(message.id) || (dbcMessage != null && (needle in dbcMessage.name.lowercase() || dbcMessage.signals.any { needle in it.name.lowercase() }))
            }
            return Node { message, _, _, _, _, _ -> message.index < matches.size && matches[message.index] }
        }

        private fun check(operator: String, value: String, number: (String) -> Double?): Check {
            fun read(text: String) = number(text) ?: fail("Can't read \"$text\" as a number")
            val range = RANGE.matchEntire(value)
            return when {
                range == null -> read(value).let { Check(operator, it, it) }
                operator in EQUALS || operator == "!=" -> Check(operator, read(range.groupValues[1]), read(range.groupValues[2]))
                else -> fail("A range needs = or !=")
            }
        }
    }

    companion object {
        private val OR = setOf("or", "||")
        private val AND = setOf("and", "&&")
        private val NOT = setOf("not", "!")
        private val EQUALS = setOf("=", "==", ":")
        private val COMPARISONS = listOf("!=", "<", "<=", ">", ">=")
        private const val OPERATORS = "==|!=|<=|>=|=|:|<|>"
        private val SPACED = Regex("""\s*($OPERATORS)\s*""")
        private val TOKEN = Regex("""\(|\)|!(?!=)|[^\s()]+""")
        private val TERM = Regex("""(\w+|[dD]\[\d+])(?:&([0-9A-Fa-f]+))?($OPERATORS)(.+)""")
        private val BYTE = Regex("""d\[?(\d+)]?""")
        private val RANGE = Regex("""(-?[\w.]+)-(-?[\w.]+)""")

        private fun hex(text: String) = text.removePrefix("0x").removePrefix("0X").toLongOrNull(16)?.toDouble()
        private fun fail(reason: String): Nothing = throw IllegalArgumentException(reason)

        /**
         * Null for blank [text]. Signal and name terms are resolved for [messages] as they are now, so compile again
         * when messages or DBCs were added. Throws IllegalArgumentException, with a message for the user, on a mistake.
         */
        fun compile(text: String, messages: Array<FrameStore.Message>, decoder: (FrameStore.Message) -> DbcMessage?): Filter? {
            val tokens = TOKEN.findAll(text.replace(',', ' ').replace(SPACED, "$1")).map { it.value }.toList()
            if (tokens.isEmpty()) return null
            val whole = Parser(tokens, messages, decoder).all()   // also finds every mistake, before the text is cut up
            val groups = split(tokens)
            return if (groups.size < 2) Filter(arrayOf(whole), listOf(tokens.joinToString(" ")))
            else Filter(Array(groups.size) { Parser(groups[it], messages, decoder).all() }, groups.map { it.joinToString(" ") })
        }

        /** The terms that stand side by side outside all brackets; the whole text as one when an `or` joins them. */
        private fun split(tokens: List<String>): List<List<String>> {
            val groups = ArrayList<List<String>>()
            var depth = 0
            var from = 0
            for ((index, token) in tokens.withIndex()) {
                if (token == "(") depth++ else if (token == ")") depth--
                if (depth > 0) continue
                if (token in OR) return listOf(tokens)
                if (token in AND) from = index + 1
                else if (token !in NOT) { groups += tokens.subList(from, index + 1); from = index + 1 }
            }
            return groups
        }
    }
}
