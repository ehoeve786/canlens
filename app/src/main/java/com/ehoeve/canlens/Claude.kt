package com.ehoeve.canlens

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Asks Claude (the Anthropic API, with the user's own key) what Decode's events were and what their fields mean. */
object Claude {
    private const val MODEL = "claude-opus-5-5"
    private const val SYSTEM = "You help reverse-engineer the CAN messages of the user's car. Each event is a moment when some " +
        "undocumented fields (labelled F1, F2, ...) changed together. You get every change of each field over the whole log, and " +
        "the same for the documented signals that changed around that moment. Say what happened at the event, and for each field " +
        "what the field itself means over the whole log: its states, not the moment (a bit that is 1 from getting in until " +
        "parking is \"drive ready\", not \"car powered down\"). Answer with only a JSON array, one object per event: " +
        "{\"event\": its number, \"name\": two to four plain words for what happened, \"why\": one short sentence naming the signals " +
        "that show it, \"fields\": [{\"field\": its label, \"name\": two to four plain words for what it means, \"values\": " +
        "{\"0\": \"OFF\", \"1\": \"READY\"} (a short upper-case name for each value it takes), \"same\": [labels of other fields of " +
        "this event in the same message that are really one field with it]}]}. Use \"unclear\" for any name you can't tell from the data: " +
        "never a placeholder (no letters or numbers to tell fields apart, no \"status flag A\"), and no words like flag or state that " +
        "only repeat what kind of field it is."

    /** Claude's answer for the events in [report], one object per event. Blocking: call it off the main thread. */
    fun ask(key: String, report: String): List<JSONObject> {
        val body = JSONObject().put("model", MODEL).put("max_tokens", 4000).put("system", SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", report)))
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpsURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 180_000
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.setRequestProperty("content-type", "application/json")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = c.responseCode in 200..299
            val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) throw IOException(runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault("HTTP ${c.responseCode}"))
            val content = JSONObject(text).getJSONArray("content")
            val answer = (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.getString("text") }
            val start = answer.indexOf('[')
            val end = answer.lastIndexOf(']')
            if (start < 0 || end < start) throw IOException("no answer in the reply")
            val events = JSONArray(answer.substring(start, end + 1))
            return (0 until events.length()).map { events.getJSONObject(it) }
        } finally {
            c.disconnect()
        }
    }
}
