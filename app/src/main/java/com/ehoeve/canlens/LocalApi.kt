package com.ehoeve.canlens

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * What is open in CANLens, for an assistant working through the owner's own Termux (PocketPilot's termux_run):
 * HTTP on the abstract Unix socket [NAME], answered only to Termux's user id, so no other app on the phone can read
 * the car's data or plot through it, and nothing is reachable from the network. With curl:
 * `curl -s --abstract-unix-socket canlens 'http://x/status'`. GET only; JSON back; times in seconds of the timeline.
 *
 * - /status: frames, messages, span, source, cursor, markers, what is plotted
 * - /messages?q=: messages whose id or DBC name holds q, with their rates
 * - /signals?q=: signals (DBC ones, then Decode's suggestions) whose name, message name or id holds q
 * - /values?keys=bus:id:signal,…&from=&to=: each signal's value at the start and at every change (2000 at most)
 * - /changed?from=&to=: the rarely-changing named signals that changed in that stretch, rarest first
 * - /plot?list=: a plot list (`bus:id:signal`, `@seconds=note`), plotted and marked on the screen
 * - /stats: the comma's and the DashKit's stats and latest log lines, when streaming from them
 */
class LocalApi(private val model: AppModel, private val context: Context) {
    @Volatile private var server: LocalServerSocket? = null

    fun start() {
        val server = runCatching { LocalServerSocket(NAME) }.getOrNull() ?: return
        this.server = server
        thread(isDaemon = true, name = "canlens-api") {
            while (this.server === server) {
                val socket = try { server.accept() } catch (e: IOException) { return@thread }
                runCatching {
                    socket.use {
                        val termux = runCatching { context.packageManager.getApplicationInfo("com.termux", 0).uid }.getOrDefault(-1)
                        if (termux < 0 || it.peerCredentials.uid != termux) return@use
                        it.soTimeout = 5000
                        val (code, body) = try { 200 to answer(requestLine(it.inputStream)) } catch (e: Exception) {
                            400 to JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
                        }
                        val bytes = body.toByteArray()
                        it.outputStream.write("HTTP/1.0 $code ${if (code == 200) "OK" else "Bad Request"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
                        it.outputStream.flush()
                    }
                }
            }
        }
    }

    /** Frees the name for the next model (the app reopened): a connection wakes the accept that close leaves waiting. */
    fun stop() {
        val server = server ?: return
        this.server = null
        runCatching { server.close() }
        runCatching { LocalSocket().use { it.connect(LocalSocketAddress(NAME)) } }
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
            "/stats" -> JSONObject()
                .put("comma", model.live.comma?.let { c -> JSONObject().put("device", c.device).put("buses", c.buses).put("processes", c.processes).put("log", JSONArray(c.log().takeLast(LOG_LINES))) } ?: JSONObject.NULL)
                .put("dashkit", (model.live.commaDashKit ?: model.tx.status)?.let { d ->
                    JSONObject().put("build", d.build).put("metrics", d.metrics?.let { JSONArray(it.toList()) } ?: JSONObject.NULL).put("log", JSONArray(d.log().takeLast(LOG_LINES)))
                } ?: JSONObject.NULL)
            else -> throw IOException("unknown path $path: /status /messages /signals /values /changed /plot /stats")
        }.toString()
    }

    companion object {
        const val NAME = "canlens"
        private const val LIMIT = 500
        private const val POINTS = 2000
        private const val LOG_LINES = 100
    }
}
