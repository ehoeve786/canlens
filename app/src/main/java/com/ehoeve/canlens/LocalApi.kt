package com.ehoeve.canlens

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * What is open in CANLens, for an assistant working through the owner's own Termux (PocketPilot's termux_run):
 * HTTP on the loopback address, which on Android is [::1]:[PORT] (IPv6), nothing reachable from the network, answered only with the token CANLens keeps in
 * `Download/CANLens/api-token.txt` (an app can't reach another's Unix socket, so the token stands in for who asks:
 * apps that may read Downloads could read the logs there anyway). With curl:
 * `curl -s "http://[::1]:62403/status?t=$(cat /sdcard/Download/CANLens/api-token.txt)"`.
 * GET only; JSON back; times in seconds of the timeline.
 *
 * - /status: frames, messages, span, source, cursor, markers, what is plotted
 * - /messages?q=: messages whose id or DBC name holds q, with their rates
 * - /signals?q=: signals (DBC ones, then Decode's suggestions) whose name, message name or id holds q
 * - /values?keys=bus:id:signal,…&from=&to=: each signal's value at the start and at every change (2000 at most)
 * - /changed?from=&to=: the rarely-changing named signals that changed in that stretch, rarest first
 * - /plot?list=: a plot list (`bus:id:signal`, `@seconds=note`), plotted and marked on the screen
 * - /stats: the comma's and the DashKit's stats and latest log lines, when streaming from them
 * - /rename?key=bus:id:signal&name=VCSEC_driverPresent[&values=1:IN,0:OUT]: renames one signal, a DBC's or one of
 *   Decode's suggestions, with value names if given
 * - /name?event=1&name=driver out: names an event's fields as the DBCs do (VCFRONT_driverOut), as its chip in Decode does
 */
class LocalApi(private val model: AppModel, private val context: Context, preferences: android.content.SharedPreferences) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var stopped = false
    private val token = preferences.getString("apiToken", null) ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }.also { preferences.edit().putString("apiToken", it).apply() }

    fun start() {
        thread(isDaemon = true, name = "canlens-api") {
            // the screen's model of a reopened app starts before the old one lets the port go: tried again until it does
            var bound: ServerSocket? = null
            while (bound == null && !stopped) bound = runCatching { ServerSocket(PORT, 8, InetAddress.getLoopbackAddress()) }.getOrElse { Thread.sleep(1000); null }
            val server = bound ?: return@thread
            this.server = server
            if (stopped) { server.close(); return@thread }
            runCatching { publishToken() }
            while (true) {
                val socket = try { server.accept() } catch (e: IOException) { return@thread }
                runCatching {
                    socket.use {
                        it.soTimeout = 5000
                        val (code, body) = try {
                            val target = requestLine(it.inputStream)
                            val given = Regex("[?&]t=([0-9a-f]+)").find(target)?.groupValues?.get(1).orEmpty()
                            if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) 403 to JSONObject().put("error", "wrong or missing t= token").toString()
                            else 200 to answer(target)
                        } catch (e: Exception) {
                            400 to JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
                        }
                        val bytes = body.toByteArray()
                        it.outputStream.write("HTTP/1.0 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
                        it.outputStream.flush()
                    }
                }
            }
        }
    }

    /** Frees the port for the next model (the app reopened). */
    fun stop() { stopped = true; runCatching { server?.close() } }

    /** Writes the token to `Download/CANLens/api-token.txt`, over the app's own copy when it has one. */
    private fun publishToken() {
        val resolver = context.contentResolver
        val folder = Environment.DIRECTORY_DOWNLOADS + "/CANLens/"
        val existing = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(folder, TOKEN_FILE), null)
            ?.use { if (it.moveToFirst()) ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0)) else null }
        val uri = existing ?: resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, TOKEN_FILE); put(MediaStore.Downloads.MIME_TYPE, "text/plain"); put(MediaStore.Downloads.RELATIVE_PATH, folder)
        }) ?: return
        resolver.openOutputStream(uri, "wt")?.use { it.write(token.toByteArray()) }
    }

    /** The request's target ("/values?keys=…"), from its first line, 8 KB at most. */
    private fun requestLine(input: java.io.InputStream): String {
        val line = StringBuilder()
        while (line.length < 8192) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) line.append(c.toChar())
        }
        val parts = line.split(' ')
        if (parts.size < 2 || parts[0] != "GET") throw IOException("GET only")
        return parts[1]
    }

    private fun answer(target: String): String {
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val store = model.store
        fun time(name: String, default: Int) = query[name]?.toFloat()?.let(::ticks) ?: default
        fun key(k: PlotKey) = "${k.bus}:${hexId(k.id)}:${k.signal}"
        return when (path) {
            "/status" -> JSONObject()
                .put("frames", store.frameCount).put("messages", store.messages.size)
                .put("start", seconds(store.firstTime)).put("end", seconds(store.lastTime))
                .put("source", model.live.source.name).put("cursor", seconds(model.trace.cursorNow))
                // the time of day of timeline 0 (ms since 1970), when the data has a clock: any time t is this + t s
                .put("clockAtZeroMs", store.wallMicros(0)?.div(1000) ?: JSONObject.NULL)
                .put("markers", JSONArray(model.trace.markers.map { JSONObject().put("t", seconds(it.time)).put("note", it.note) }))
                .put("plotted", JSONArray(model.plot.keys.map(::key)))
            "/messages" -> {
                val q = query["q"].orEmpty().lowercase().removePrefix("0x")
                JSONArray(store.messages.mapNotNull { m ->
                    val name = model.dbc.decoder(m.bus, m.id)?.name
                    if (q.isNotEmpty() && q !in hexId(m.id).lowercase() && name?.lowercase()?.contains(q) != true) null
                    else JSONObject().put("key", "${m.bus}:${hexId(m.id)}").put("name", name ?: JSONObject.NULL).put("frames", m.count).put("hz", "%.1f".format(m.rate))
                }.take(LIMIT))
            }
            "/signals" -> JSONArray(model.signalsMatching(query["q"].orEmpty(), LIMIT).map { (k, message) -> JSONObject().put("key", key(k)).put("message", message) })
            "/values" -> {
                val from = time("from", Int.MIN_VALUE)
                val to = time("to", Int.MAX_VALUE)
                val out = JSONObject()
                for (k in model.plotList(query["keys"].orEmpty())?.first.orEmpty()) {
                    val (decoder, signal) = model.signalOf(k) ?: run { out.put(key(k), JSONObject.NULL); null } ?: continue
                    val message = store.messages.firstOrNull { it.bus == k.bus && it.id == k.id } ?: continue
                    val series = store.series(message, decoder, signal, from, to)
                    val points = JSONArray()
                    for (i in 0 until series.size) {
                        if (i > 0 && series.values[i] == series.values[i - 1]) continue
                        if (points.length() == POINTS) break
                        points.put(JSONArray().put(series.times[i]).put(series.values[i]))
                    }
                    out.put(key(k), JSONObject().put("unit", signal.unit).put("names", JSONObject(signal.valueNames.mapKeys { it.key.toString() }))
                        .put("gaps", JSONArray(series.gaps.toList())).put("changes", points))
                }
                out.toString()
            }
            "/changed" -> JSONArray(model.changedSignals(time("from", Int.MIN_VALUE), time("to", Int.MAX_VALUE)).take(LIMIT)
                .map { (k, changes) -> JSONObject().put("key", key(k)).put("changesInLog", changes) })
            "/plot" -> {
                val list = model.plotList(query["list"].orEmpty()) ?: throw IOException("no bus:id:signal or @seconds in list")
                runBlocking(Dispatchers.Main) { model.applyPlotList(list) }
                JSONObject().put("status", model.status)
            }
            "/name" -> {
                val number = query["event"]?.toIntOrNull() ?: throw IOException("event=<number> wanted")
                val count = model.signals.autoResults.count { it.signal.name.startsWith("E${number}_") }
                if (count == 0) throw IOException("no event E$number among Decode's suggestions")
                runBlocking(Dispatchers.Main) { model.signals.nameEvent(number, query["name"].orEmpty()) }
                JSONObject().put("named", count)
            }
            "/rename" -> {
                val k = model.plotList(query["key"].orEmpty())?.first?.singleOrNull() ?: throw IOException("key=bus:id:signal wanted")
                val name = query["name"].orEmpty()
                if (!Regex("[A-Za-z_]\\w*").matches(name)) throw IOException("name: letters, digits and _, not starting with a digit")
                val message = store.messages.firstOrNull { it.bus == k.bus && it.id == k.id } ?: throw IOException("${key(k)}: message not in the open data")
                val (_, signal, saved) = model.signalOf(k) ?: throw IOException("${key(k)}: no such signal")
                val values = query["values"]?.split(',')?.mapNotNull { v -> v.substringBefore(':').trim().toLongOrNull()?.let { it to v.substringAfter(':').trim() } }?.toMap()
                val renamed = signal.renamed(name).also { s -> if (values != null) s.valueNames = values }
                val suggestion = if (saved) null else model.signals.autoResults.firstOrNull { it.message === message && it.signal.name == k.signal }
                    ?: throw IOException("${key(k)}: not a DBC signal or one of Decode's suggestions")
                runBlocking(Dispatchers.Main) { if (suggestion == null) model.dbc.saveSignal(message, renamed, k.signal) else model.signals.rename(suggestion, renamed) }
                JSONObject().put("renamed", key(PlotKey(k.bus, k.id, name)))
            }
            "/stats" -> JSONObject()
                .put("comma", model.live.comma?.let { c -> JSONObject().put("device", c.device).put("buses", c.buses).put("processes", c.processes).put("log", JSONArray(c.log().takeLast(LOG_LINES))) } ?: JSONObject.NULL)
                .put("dashkit", (model.live.commaDashKit ?: model.tx.status)?.let { d ->
                    JSONObject().put("build", d.build).put("metrics", d.metrics?.let { JSONArray(it.toList()) } ?: JSONObject.NULL).put("log", JSONArray(d.log().takeLast(LOG_LINES)))
                } ?: JSONObject.NULL)
            else -> throw IOException("unknown path $path: /status /messages /signals /values /changed /plot /stats /name /rename")
        }.toString()
    }

    companion object {
        const val PORT = 62403
        private const val TOKEN_FILE = "api-token.txt"
        private const val LIMIT = 500
        private const val POINTS = 2000
        private const val LOG_LINES = 100
    }
}
