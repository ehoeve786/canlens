package com.ehoeve.canlens

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Asks Claude (the Anthropic API, with the user's own key) what Decode's events were. */
object Claude {
    private const val MODEL = "claude-sonnet-5-5"
    private const val SYSTEM = "You help reverse-engineer the CAN messages of the user's car. Each event is a moment when some " +
        "undocumented fields changed together; with them come the documented signals that changed around the same moment. " +
        "Say what the driver or the car most likely did then. Answer with only a JSON array, one object per event: " +
        "{\"event\": its number, \"name\": two to four plain lowercase words for what happened (\"shift out of park\", " +
        "\"driver door opened\"), \"why\": one short sentence naming the signals that show it}. When the signals don't make it " +
        "clear, the name is \"unclear\"."

    /** Event number to Claude's name for it and why, for the events in [report]. Blocking: call it off the main thread. */
    fun nameEvents(key: String, report: String): Map<Int, Pair<String, String>> {
        val body = JSONObject().put("model", MODEL).put("max_tokens", 2000).put("system", SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", report)))
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpsURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 120_000
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
            return (0 until events.length()).map { events.getJSONObject(it) }.associate { it.getInt("event") to (it.getString("name") to it.optString("why")) }
        } finally {
            c.disconnect()
        }
    }
}
