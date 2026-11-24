package com.ehoeve.canpilot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehoeve.canpilot.core.Filter
import com.ehoeve.canpilot.core.FrameStore

private class Command(val title: String, val detail: String, val run: () -> Unit)

private const val MAX_EACH = 6

/** One box that goes anywhere: a time, a message, a signal to plot, a filter for the frames, or a tool. */
@Composable
fun CommandSheet(model: AppModel, onClose: () -> Unit, onOpen: (FrameStore.Message) -> Unit, onTab: (Tab) -> Unit, onFind: () -> Unit, onAuto: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val revision = model.revision
    val commands = remember(query, revision) {
        val store = model.store
        val text = query.trim()
        val needle = text.lowercase()
        val found = ArrayList<Command>()
        if (text.isNotEmpty()) {
            val time = text.removeSuffix("s").trim().toFloatOrNull()
            if (time != null && time >= seconds(store.firstTime) && time <= seconds(store.lastTime))
                found += Command("Go to %.2f s".format(time), "Move the cursor") { model.trace.cursor = ticks(time); model.trace.followLive = false }
            var messages = 0
            for (message in store.messages) {
                val name = model.dbc.decoder(message.bus, message.id)?.name
                if (needle !in hexId(message.id).lowercase() && name?.lowercase()?.contains(needle) != true) continue
                found += Command("${hexId(message.id)}  ${name ?: ""}", "Open the message · bus ${message.bus}") { onOpen(message) }
                if (++messages == MAX_EACH) break
            }
            for ((key, messageName) in model.signalsMatching(text, MAX_EACH))
                found += Command(key.signal, "Plot the signal · $messageName") { model.plot.keys += key; onTab(Tab.PLOTS) }
            if (runCatching { Filter.compile(text, store.messages) { model.dbc.decoder(it.bus, it.id) } }.isSuccess)
                found += Command("Filter the frames", text) { model.trace.filter = text; model.trace.fixed = false; model.trace.keepFilter(); onTab(Tab.FRAMES) }
        }
        if (needle in "find a signal") found += Command("Find a signal", "By its value at known moments, or by a signal it moves with", onFind)
        if (needle in "decode everything") found += Command("Decode everything", "Work out the layout of every message without a DBC entry", onAuto)
        found
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.imePadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                placeholder = { Text("Message, signal, time or filter") }, leadingIcon = { Icon(Icons.Default.Search, null) })
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(commands) { command ->
                    Column(Modifier.fillMaxWidth().clickable { command.run(); onClose() }.heightIn(min = 56.dp).padding(vertical = 8.dp)) {
                        Text(command.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Hint(command.detail)
                    }
                }
            }
        }
    }
}
