package com.ehoeve.canlens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehoeve.canlens.core.Filter
import com.ehoeve.canlens.core.FrameStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val FILTER_HELP = """bus=1 id=249       all terms must hold
id=100-1FF len>4   hex ids; a-b is a range
d0=FF d2&0F!=3     data bytes; & masks bits
t=73.5-75          seconds
DAS_lssState=FAULT signal: number or name
changed            bytes differ from last frame
stalk              part of an id or a name
or  not  ( )       combine terms
Tap a row: cursor. Hold it: open its message."""

private const val HEX = "0123456789ABCDEF"
private val CELL = TextStyle(fontFamily = MONO, fontSize = 12.sp, lineHeight = 16.sp)

/** The payload as hex bytes, with the bytes that differ from the message's previous frame set off. Bit 7 of [changed] covers byte 7 and all after it. */
private fun payload(data: ByteArray, start: Int, length: Int, changed: Int, highlight: SpanStyle): AnnotatedString = buildAnnotatedString {
    for (b in 0 until length) {
        if (b > 0) append(' ')
        val value = data[start + b].toInt() and 0xFF
        val differs = (changed shr minOf(b, 7)) and 1 != 0
        if (differs) pushStyle(highlight)
        append(HEX[value ushr 4]); append(HEX[value and 15])
        if (differs) pop()
    }
}

/**
 * The frames table. Stream lists every frame in order; Fixed keeps one row per message, showing what it last sent
 * at the cursor. Both obey the filter, and both set off the bytes that changed. A tap on a stream row moves the cursor
 * there and selects its message for the Message tab; a long press, or a tap on the id, opens it.
 */
@Composable
fun TraceScreen(model: AppModel, selected: FrameStore.Message?, onSelect: (FrameStore.Message) -> Unit, onOpen: (FrameStore.Message) -> Unit) {
    val store = model.store
    val trace = model.trace
    val revision = model.revision
    val messages = store.messages
    val dbcs = model.dbc.files.toList()
    var help by remember { mutableStateOf(false) }

    val compiled = remember(trace.filter, dbcs, messages.size) { runCatching { Filter.compile(trace.filter, messages) { model.dbc.decoder(it.bus, it.id) } } }
    // while a filter is being typed and doesn't read yet, the last one that did stays in force
    val lastGood = remember { arrayOfNulls<Filter>(1) }
    if (compiled.isSuccess) lastGood[0] = compiled.getOrNull()
    val filter = lastGood[0]

    val cursor = trace.cursorNow
    val following = trace.following
    val scan = remember(filter) { store.RowScan(filter) }
    val rows by produceState<FrameStore.Rows?>(null, scan, revision, trace.fixed) {
        if (!trace.fixed) value = withContext(Dispatchers.Default) { scan.update() }
    }
    val latest by produceState(emptyList<FrameStore.Latest>(), filter, revision, trace.fixed, if (following) -1 else cursor) {
        if (trace.fixed) value = withContext(Dispatchers.Default) {
            store.latest(if (following) null else cursor, filter).sortedWith(compareBy({ it.message.bus }, { it.message.id }))
        }
    }

    val list = rememberLazyListState()
    val shown = rows
    // an empty table says which term of the filter is to blame
    val why by produceState<String?>(null, filter, shown, trace.fixed) {
        value = if (!trace.fixed && filter != null && shown != null && shown.size == 0 && store.frameCount > 0) withContext(Dispatchers.Default) { store.whyEmpty(filter) } else null
    }
    val cursorRow = remember(shown, cursor) { if (shown == null || shown.size == 0) -1 else shown.rowAt(cursor) }
    // the table follows the cursor: bring its row into view when it is not
    LaunchedEffect(cursorRow, scan, trace.fixed) {
        if (trace.fixed || cursorRow < 0) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || cursorRow < visible.first().index || cursorRow >= visible.last().index) list.scrollToItem(maxOf(cursorRow - 3, 0))
    }
    // scrolling by hand lets go of the live end
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect {
            if (it is DragInteraction.Start && trace.following) { trace.cursor = store.lastTime; trace.followLive = false }
        }
    }

    val highlight = SpanStyle(color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Frames", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    FilterChip(selected = !trace.fixed, onClick = { trace.fixed = false }, label = { Text("Stream") })
                    FilterChip(selected = trace.fixed, onClick = { trace.fixed = true }, label = { Text("Fixed") }, modifier = Modifier.padding(start = 8.dp))
                    // what is saved is what Stream lists: every frame, or those the filter lets through
                    SaveMenu(
                        listOf(SaveEntry("Save as candump log", "canlens-frames.log") { model.exportFrames(it, csv = false, window = false) },
                            SaveEntry("Save as CSV", "canlens-frames.csv") { model.exportFrames(it, csv = true, window = false) }) +
                            if (trace.markA == null || trace.markB == null) emptyList()
                            else listOf(SaveEntry("A–B as candump log", "canlens-a-b.log") { model.exportFrames(it, csv = false, window = true) },
                                SaveEntry("A–B as CSV", "canlens-a-b.csv") { model.exportFrames(it, csv = true, window = true) })
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                trace.filter, { trace.filter = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true, textStyle = CELL.copy(fontSize = 14.sp),
                placeholder = { Text("bus=1 id=249 d0&0F=3 changed", style = CELL.copy(fontSize = 14.sp)) },
                isError = compiled.isFailure,
                supportingText = {
                    val error = compiled.exceptionOrNull()?.message
                    if (error != null || !trace.fixed) Text(error ?: why ?: "${shown?.size ?: 0} of ${store.frameCount} frames")
                    // Fixed has each message as it stood at the cursor: early in a log that is only the few that had sent by then
                    else Row {
                        Text("${latest.size} of ${messages.size} messages" + if (following) "" else ", as they stood at %.2f s".format(seconds(cursor)))
                        if (!following && cursor < store.lastTime) Text("Go to the end", Modifier.padding(start = 12.dp).clickable { trace.cursor = store.lastTime }, color = MaterialTheme.colorScheme.primary)
                    }
                },
                trailingIcon = {
                    Row {
                        if (trace.filter.isNotEmpty()) IconButton(onClick = { trace.filter = "" }) { Icon(Icons.Default.Close, "Clear filter") }
                        IconButton(onClick = { help = !help }) { Icon(Icons.Default.Info, "How to write a filter") }
                    }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (compiled.isSuccess) trace.keepFilter(); defaultKeyboardAction(ImeAction.Done) }),
            )
            if (trace.recentFilters.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (recent in trace.recentFilters) {
                    FilterChip(selected = recent == trace.filter.trim(), onClick = { trace.filter = recent }, label = { Text(recent, style = CELL, maxLines = 1) },
                        trailingIcon = { Icon(Icons.Default.Close, "Forget this filter", Modifier.size(16.dp).clickable { trace.forgetFilter(recent) }) })
                }
            }
            if (help) Text(FILTER_HELP, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), style = CELL, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                val head = CELL.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (trace.fixed) "Count" else "Time", Modifier.width(TIME_WIDTH), style = head, textAlign = TextAlign.End)
                Text("B", Modifier.width(BUS_WIDTH), style = head, textAlign = TextAlign.Center)
                Text("ID", Modifier.width(ID_WIDTH), style = head)
                Text("Data · name", style = head)
            }
            HorizontalDivider()
            if (trace.fixed) LazyColumn(Modifier.fillMaxSize()) {
                items(latest, key = { it.message.index }) { row ->
                    FrameRow(row.count.toString(), row.message, model.dbc.decoder(row.message.bus, row.message.id)?.name, payload(row.data, 0, row.data.size, row.changed, highlight),
                        selected = row.message === selected, onClick = { onOpen(row.message) }, onOpen = { onOpen(row.message) })
                }
            } else if (shown != null) LazyColumn(Modifier.fillMaxSize(), state = list) {
                items(shown.size) { row ->
                    val position = shown.position(row)
                    val chunk = shown.chunks[position ushr FrameStore.CHUNK_BITS]
                    val index = position and (FrameStore.CAPACITY - 1)
                    val message = messages.getOrNull(chunk.message[index].toInt() and 0xFFFF)
                    if (message != null) {
                        val time = chunk.time[index]
                        val start = chunk.start(index)
                        FrameRow("%.4f".format(time / FrameStore.TICKS_PER_SECOND), message, model.dbc.decoder(message.bus, message.id)?.name,
                            payload(chunk.blob, start, chunk.end[index] - start, chunk.changed[index].toInt(), highlight),
                            selected = row == cursorRow, onClick = { trace.cursor = time; trace.followLive = false; onSelect(message) }, onOpen = { onOpen(message) })
                    }
                }
            }
        }
    }
}

private val TIME_WIDTH = 66.dp
private val BUS_WIDTH = 22.dp
private val ID_WIDTH = 30.dp
private val LONG_ID_WIDTH = 66.dp

/** One table row. A long press, or a tap on the id, is [onOpen]; a tap elsewhere is [onClick]. */
@Composable
private fun FrameRow(first: String, message: FrameStore.Message, name: String?, data: AnnotatedString, selected: Boolean, onClick: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent).combinedClickable(onLongClick = onOpen, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(first, Modifier.width(TIME_WIDTH), style = CELL, textAlign = TextAlign.End, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message.bus.toString(), Modifier.width(BUS_WIDTH), style = CELL, textAlign = TextAlign.Center, color = BUS_COLORS[message.bus.coerceAtLeast(0) % BUS_COLORS.size])
        Text(hexId(message.id), Modifier.width(if (message.id > 0x7FF) LONG_ID_WIDTH else ID_WIDTH).clickable(onClick = onOpen), style = CELL, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(data, style = CELL, maxLines = 1, softWrap = false)
        if (name != null) Text(name, Modifier.weight(1f).padding(start = 8.dp), style = CELL, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
