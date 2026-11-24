package com.ehoeve.canpilot

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Named workspaces. A session is which logs are open and what was set up to look at them: plots, marks, markers,
 * cursor and the frames filter. It is written when the app is left or another session is opened, and the one in use
 * comes back when the app starts. DBC files are shared by all sessions.
 */
class SessionState(private val model: AppModel, context: Context, private val preferences: SharedPreferences) {
    private val folder = File(context.filesDir, "sessions").apply { mkdirs() }

    var current by mutableStateOf(preferences.getString("session", null) ?: DEFAULT); private set
    val names = mutableStateListOf<String>().apply {
        addAll((folder.list().orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") } + current).distinct().sorted())
    }

    private fun file(name: String) = File(folder, "$name.json")

    fun save() {
        if (model.busy) return   // half-loaded data is not the session
        val trace = model.trace
        val plot = model.plot
        val json = JSONObject()
            .put("logs", JSONArray(model.logs.ifEmpty { listOfNotNull(model.live.recorded) }.map { it.toString() }))
            .put("merged", JSONArray(model.mergedLogs.map { it.toString() }))
            .put("plots", JSONArray(plot.keys.map { JSONObject().put("bus", it.bus).put("id", it.id).put("signal", it.signal) }))
            .put("markers", JSONArray(trace.markers.map { JSONObject().put("time", it.time).put("note", it.note) }))
            .put("cursor", trace.cursorNow).put("markA", trace.markA ?: JSONObject.NULL).put("markB", trace.markB ?: JSONObject.NULL)
            .put("filter", trace.filter).put("fixed", trace.fixed)
            .put("overlay", plot.overlay).put("sharedScale", plot.sharedScale)
            .put("viewStart", plot.view.start.toDouble()).put("viewEnd", plot.view.end.toDouble())
        runCatching { file(current).writeText(json.toString()) }
    }

    /** Saves the session in use and opens [name]. */
    fun open(name: String) {
        if (name == current || model.busy) return
        save()
        use(name)
        load(false)
    }

    /** Saves the session in use and starts an empty one. Names keep letters, digits, spaces, dots and dashes. */
    fun create(name: String) {
        val clean = name.trim().replace(Regex("[^\\w .-]"), "_").take(40)
        if (clean.isEmpty() || clean in names || model.busy) return
        save()
        use(clean)
        names += clean
        names.sort()
        model.closeLogs()
        save()
    }

    fun delete(name: String) {
        if (model.busy) return
        file(name).delete()
        names.remove(name)
        if (name != current) return
        use(names.firstOrNull() ?: DEFAULT)
        if (current !in names) names += current
        load(false)
    }

    /** At the start of the app: the session it was left in. */
    fun restore() = load(true)

    private fun use(name: String) {
        current = name
        preferences.edit().putString("session", name).apply()
    }

    private fun load(starting: Boolean) {
        val json = runCatching { JSONObject(file(current).readText()) }.getOrNull()
        if (json == null) { if (!starting) model.closeLogs(); return }
        fun uris(key: String) = json.optJSONArray(key)?.let { list -> List(list.length()) { Uri.parse(list.getString(it)) } }.orEmpty()
        val logs = uris("logs")
        // a log that crashed the app while it was reopened at the start is not tried again
        if (starting && preferences.getBoolean(RESTORING, false)) {
            preferences.edit().putBoolean(RESTORING, false).apply()
            return
        }
        val settle = { apply(json); preferences.edit().putBoolean(RESTORING, false).apply() }
        if (logs.isEmpty()) { if (!starting) model.closeLogs(); settle(); return }
        if (starting) preferences.edit().putBoolean(RESTORING, true).commit()
        val merged = uris("merged")
        model.openLogs(logs) { if (merged.isEmpty() || model.store.frameCount == 0L) settle() else model.mergeLogs(merged, settle) }
    }

    /** Puts back what was set up; runs after the session's logs are loaded, when every part has been reset for them. */
    private fun apply(json: JSONObject) {
        val trace = model.trace
        val plot = model.plot
        plot.keys.clear()
        json.optJSONArray("plots")?.let { list -> for (i in 0 until list.length()) list.getJSONObject(i).let { plot.keys += PlotKey(it.getInt("bus"), it.getInt("id"), it.getString("signal")) } }
        json.optJSONArray("markers")?.let { list ->
            trace.markers.clear()
            for (i in 0 until list.length()) list.getJSONObject(i).let { trace.markers += Marker(it.getInt("time"), it.getString("note")) }
        }
        trace.cursor = json.optInt("cursor", trace.cursor)
        trace.markA = if (json.isNull("markA")) null else json.optInt("markA")
        trace.markB = if (json.isNull("markB")) null else json.optInt("markB")
        trace.filter = json.optString("filter")
        trace.fixed = json.optBoolean("fixed")
        plot.overlay = json.optBoolean("overlay")
        plot.sharedScale = json.optBoolean("sharedScale")
        if (json.has("viewStart") && model.store.frameCount > 0) plot.view.show(json.optDouble("viewStart").toFloat(), json.optDouble("viewEnd").toFloat())
    }

    private companion object {
        const val DEFAULT = "Default"
        const val RESTORING = "restoring"
    }
}

/** Lists the sessions: tap one to open it, or name a new one. */
@Composable
fun SessionSheet(model: AppModel, onClose: () -> Unit) {
    val session = model.session
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sessions", style = MaterialTheme.typography.titleLarge)
            Hint("A session keeps its logs, plots, marks, markers and filter. The one in use is saved when you leave the app and comes back when you open it.")
            for (other in session.names.toList()) {
                val active = other == session.current
                Surface(onClick = { session.open(other); onClose() }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(other, Modifier.weight(1f), fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                        if (active) Icon(Icons.Default.Check, "In use", tint = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { session.delete(other) }) { Icon(Icons.Default.Delete, "Delete $other") }
                    }
                }
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("New session") })
            Button(enabled = name.isNotBlank(), onClick = { session.create(name); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Start an empty session") }
        }
    }
}
