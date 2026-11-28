package com.ehoeve.canlens

import com.ehoeve.canlens.core.FrameStore
import com.ehoeve.canlens.core.Suggestion
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Decode with Claude: Decode's events (fields that changed together once), each with the named signals that changed
 * within a second of it, go to Claude with the user's own API key (a Console organization linked to their plan). The
 * names it gives come back as suggestions, ticked and listed with the named ones, to be checked like any other.
 */
object ClaudeDecode {
    private const val MODEL = "claude-sonnet-5-5"
    private val EVENT = Regex("^E(\\d+)_")

    /** The events in [suggestions], by number: their fields and moment. */
    fun events(suggestions: List<Suggestion>): Map<Int, List<Suggestion>> =
        suggestions.filter { it.at != null }.groupBy { EVENT.find(it.signal.name)?.groupValues?.get(1)?.toInt() ?: 0 }.filterKeys { it > 0 }

    /** What Claude is shown: per event, its fields' values before and after, and the named signals that changed then. */
    fun report(model: AppModel, events: Map<Int, List<Suggestion>>): String {
        val second = FrameStore.TICKS_PER_SECOND.toInt()
        val out = StringBuilder("Car: Tesla Model Y (legacy). Buses: 0 party, 1 vehicle, 2 Autopilot side, 3 chassis (DashKit), 8 openpilot's carState.\n")
        for ((number, fields) in events) {
            val at = fields[0].at!!
            out.append("\nEvent E$number at %.2f s:\n".format(seconds(at)))
            for (s in fields) out.append("  field ${s.message.bus}:${hexId(s.message.id)}:${s.signal.name} bits ${s.signal.start}-${s.signal.start + s.signal.size - 1}: ${changes(model, PlotKey(s.message.bus, s.message.id, s.signal.name), at - second, at + second)}\n")
            for ((key, count) in model.changedSignals(at - second, at + second).take(15))
                out.append("  named ${key.bus}:${hexId(key.id)}:${key.signal} (changes $count times in the log): ${changes(model, key, at - second, at + second)}\n")
        }
        return out.toString()
    }

    /** "3 -> 0 at 2.87 s, …" for [key] in the stretch, with the DBC's value names. */
    private fun changes(model: AppModel, key: PlotKey, from: Int, to: Int): String {
        val (message, signal) = model.signalOf(key) ?: return "?"
        val frames = model.store.messages.firstOrNull { it.bus == key.bus && it.id == key.id } ?: return "?"
        val series = model.store.series(frames, message, signal, from, to)
        fun name(v: Float) = signal.valueNames[v.toLong()] ?: "%g".format(v)
        return (1 until series.size).filter { series.values[it] != series.values[it - 1] }.take(6)
            .joinToString { "${name(series.values[it - 1])} -> ${name(series.values[it])} at %.2f s".format(series.times[it]) }.ifEmpty { "no change" }
    }

    /**
     * Asks Claude to name the events in [report]; returns (event, name, value names by field key, why). Blocking: call
     * it off the main thread. The name comes back as words ("driver out"), made into DBC style by nameEvent.
     */
    fun ask(apiKey: String, report: String): List<Answer> {
        val tool = JSONObject().put("name", "name_events").put("description", "Name each event and its fields' values.")
            .put("input_schema", JSONObject().put("type", "object").put("required", JSONArray().put("events")).put("properties", JSONObject().put("events",
                JSONObject().put("type", "array").put("items", JSONObject().put("type", "object").put("required", JSONArray().put("event").put("name").put("why"))
                    .put("properties", JSONObject()
                        .put("event", JSONObject().put("type", "integer"))
                        .put("name", JSONObject().put("type", "string").put("description", "what happened, a few plain words, e.g. 'shift out of park'"))
                        .put("why", JSONObject().put("type", "string").put("description", "one sentence: the named signals that show it"))
                        .put("values", JSONObject().put("type", "object").put("description", "per field key, its values' meanings, e.g. {\"1:33B:E1_FLAG_8\": {\"1\": \"PARKED\", \"0\": \"DRIVING\"}}")))))))
        val body = JSONObject().put("model", MODEL).put("max_tokens", 4000)
            .put("system", "You reverse-engineer CAN data. For each event, decide from the named signals that changed with it what the driver or car did, " +
                "and what the event's fields' values mean. Say nothing you can't back with those signals; leave out an event you can't explain.")
            .put("tools", JSONArray().put(tool)).put("tool_choice", JSONObject().put("type", "tool").put("name", "name_events"))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", report)))
        val connection = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("x-api-key", apiKey)
            connection.setRequestProperty("anthropic-version", "2023-06-01")
            connection.setRequestProperty("content-type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val text = (if (code < 400) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) throw IOException("Claude: HTTP $code " + (runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull() ?: text.take(200)))
            val content = JSONObject(text).getJSONArray("content")
            val input = (0 until content.length()).map { content.getJSONObject(it) }.firstOrNull { it.optString("type") == "tool_use" }
                ?.getJSONObject("input") ?: throw IOException("Claude gave no names")
            val events = input.getJSONArray("events")
            return (0 until events.length()).map { i ->
                val e = events.getJSONObject(i)
                val values = e.optJSONObject("values")?.let { v -> v.keys().asSequence().associateWith { k -> v.getJSONObject(k).let { m -> m.keys().asSequence().mapNotNull { n -> n.toLongOrNull()?.let { it to m.getString(n) } }.toMap() } } }.orEmpty()
                Answer(e.getInt("event"), e.getString("name"), values, e.optString("why"))
            }
        } finally {
            connection.disconnect()
        }
    }

    class Answer(val event: Int, val name: String, val values: Map<String, Map<Long, String>>, val why: String)
}

/** The saved Claude API key, in the app's private preferences (not backed up: allowBackup is off). */
private fun AppModel.prefs() = getApplication<android.app.Application>().getSharedPreferences("canlens", android.content.Context.MODE_PRIVATE)
fun AppModel.claudeKey(): String? = prefs().getString("claudeKey", null)
fun AppModel.saveClaudeKey(key: String) = prefs().edit().putString("claudeKey", key.trim()).apply()

/** Decode with Claude: names Decode's events with the saved key; how it went shows in [AppModel.status]. */
fun AppModel.decodeWithClaude() {
    val key = claudeKey() ?: run { status = "Add your Claude API key first"; return }
    val events = ClaudeDecode.events(signals.autoResults)
    if (events.isEmpty()) { status = "No events to name: Decode first"; return }
    status = "Asking Claude about ${events.size} events…"
    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { ClaudeDecode.ask(key, ClaudeDecode.report(this@decodeWithClaude, events)) } }
        result.onSuccess { answers -> applyClaude(answers, events.size) }.onFailure { status = it.message ?: "Claude couldn't be reached" }
    }
}

/** Claude's names applied: value names on the fields as still called, then each event's name as its chip would. */
private fun AppModel.applyClaude(answers: List<ClaudeDecode.Answer>, asked: Int) {
    for (a in answers) {
        for ((field, names) in a.values) {
            val s = signals.autoResults.firstOrNull { "${it.message.bus}:${hexId(it.message.id)}:${it.signal.name}".equals(field, true) } ?: continue
            signals.rename(s, s.signal.renamed(s.signal.name).also { it.valueNames = names })
        }
        signals.nameEvent(a.event, a.name)
    }
    status = "Claude named ${answers.size} of $asked events. " + answers.joinToString(" ") { "E${it.event} ${it.name}: ${it.why}" }
}
