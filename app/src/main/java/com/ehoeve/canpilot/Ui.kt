package com.ehoeve.canpilot

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ehoeve.canpilot.core.DbcMessage
import com.ehoeve.canpilot.core.FrameStore
import com.ehoeve.canpilot.core.Signal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val MONO = FontFamily.Monospace
val SIGNAL_COLORS = listOf(0xFF4FC3F7, 0xFFFFB74D, 0xFF81C784, 0xFFE57373, 0xFFBA68C8, 0xFFFFF176, 0xFF4DB6AC, 0xFFF06292).map { Color(it) }

fun seconds(time: Int) = time / FrameStore.TICKS_PER_SECOND.toFloat()
fun ticks(seconds: Float) = (seconds * FrameStore.TICKS_PER_SECOND).toInt()
fun hexId(id: Int) = if (id in 0..0x7FF) "%03X".format(id) else "%08X".format(id)
fun hexBytes(data: ByteArray) = buildString(data.size * 3) { for (b in data) { append("%02X".format(b.toInt() and 0xFF)).append(' ') } }.trimEnd()

fun format(signal: Signal, raw: Long): String {
    signal.valueNames[raw]?.let { return "$it ($raw)" }
    val value = signal.physical(raw)
    val number = if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString() else "%.4f".format(value).trimEnd('0').trimEnd('.')
    return if (signal.unit.isEmpty()) number else "$number ${signal.unit}"
}

@Composable
fun App(model: AppModel = viewModel()) {
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    model.revision   // re-resolve the selection when the data changes
    val message = selected?.let { key -> model.store.messages.firstOrNull { it.bus == key.first && it.id == key.second } }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Box(Modifier.weight(1f)) {
                    if (message == null) MessageList(model) { selected = it.bus to it.id }
                    else {
                        BackHandler { selected = null }
                        MessageDetail(model, message) { selected = null }
                    }
                }
                TimeBar(model)
            }
        }
    }
}

// ---- message list

@Composable
private fun MessageList(model: AppModel, onOpen: (FrameStore.Message) -> Unit) {
    var filter by remember { mutableStateOf("") }
    var changedOnly by remember { mutableStateOf(false) }
    var showDbcs by remember { mutableStateOf(false) }
    var showLive by remember { mutableStateOf(false) }
    val openLogs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.openLogs(it) }
    val revision = model.revision
    val a = model.markA
    val b = model.markB
    // bits that flipped between the marks and at no other time, for every message
    val activity by produceState<Array<FrameStore.BitActivity?>?>(null, changedOnly, a, b, revision) {
        value = if (changedOnly && a != null && b != null) withContext(Dispatchers.Default) { model.store.bitActivity(minOf(a, b), maxOf(a, b)) } else null
    }
    val dbcs = model.dbcs.toList()
    val rows = remember(revision, filter, dbcs, activity) {
        val needle = filter.trim().lowercase()
        val found = activity
        model.store.messages.map { it to model.decoder(it.bus, it.id) }
            .filter { (m, d) -> needle.isEmpty() || needle in hexId(m.id).lowercase() || (d != null && (needle in d.name.lowercase() || d.signals.any { s -> needle in s.name.lowercase() })) }
            .filter { (m, _) -> found == null || (found.getOrNull(m.index)?.onlyInside ?: 0) > 0 }
            .let { list -> if (found == null) list.sortedWith(compareBy({ it.first.bus }, { it.first.id })) else list.sortedByDescending { found[it.first.index]!!.onlyInside } }
    }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { openLogs.launch(arrayOf("*/*")) }, enabled = !model.busy) { Text("Open logs") }
            OutlinedButton(onClick = { showDbcs = true }) { Text("DBC (${dbcs.size})") }
            if (model.live == LiveSource.NONE) OutlinedButton(onClick = { showLive = true }) { Text("Live") }
            else {
                Button(onClick = { model.stopLive() }) { Text("Stop") }
                FilterChip(selected = model.recording != null, onClick = { model.setRecording(model.recording == null) }, label = { Text("Record") })
            }
            FilterChip(selected = changedOnly, onClick = { changedOnly = !changedOnly }, label = { Text("Changed only in A–B") })
        }
        Text(model.status, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (changedOnly && (a == null || b == null)) {
            Text("Set marks A and B around the moment you did something in the car. This lists the messages with bits that changed then and at no other time.",
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
        }
        OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp), singleLine = true, placeholder = { Text("Filter by id, message or signal") })
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.first.index }) { (m, decoder) ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(m) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row {
                        Text("${m.bus}  ${hexId(m.id)}", fontFamily = MONO, fontWeight = FontWeight.Bold)
                        Text(decoder?.name ?: "", Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val onlyInside = activity?.getOrNull(m.index)?.onlyInside
                        Text(if (onlyInside != null) "$onlyInside bits" else "%.0f Hz".format(m.rate), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(hexBytes(m.data), fontFamily = MONO, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
    if (showDbcs) DbcDialog(model) { showDbcs = false }
    if (showLive) LiveDialog(model) { showLive = false }
}

@Composable
private fun DbcDialog(model: AppModel, onClose: () -> Unit) {
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) model.openDbc(it) }
    var exporting by remember { mutableStateOf(-1) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && exporting in model.dbcs.indices) model.exportDbc(exporting, uri)
    }
    val buses = (listOf(0, 1, 2, 3) + model.store.messages.map { it.bus }).distinct().sorted()
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        dismissButton = { TextButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("Add DBC") } },
        title = { Text("DBC files") },
        text = {
            Column {
                if (model.dbcs.isEmpty()) Text("None yet. Add one for all buses, or one per bus.")
                model.dbcs.forEachIndexed { index, loaded ->
                    var menu by remember { mutableStateOf(false) }
                    Text(loaded.dbc.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            TextButton(onClick = { menu = true }) { Text(if (loaded.bus == ALL_BUSES) "All buses" else "Bus ${loaded.bus}") }
                            DropdownMenu(menu, { menu = false }) {
                                for (bus in listOf(ALL_BUSES) + buses) {
                                    DropdownMenuItem(text = { Text(if (bus == ALL_BUSES) "All buses" else "Bus $bus") }, onClick = { model.setDbcBus(index, bus); menu = false })
                                }
                            }
                        }
                        TextButton(onClick = { exporting = index; export.launch(loaded.dbc.name) }) { Text("Export") }
                        TextButton(onClick = { model.removeDbc(index) }) { Text("Remove") }
                    }
                }
            }
        },
    )
}

@Composable
private fun LiveDialog(model: AppModel, onClose: () -> Unit) {
    var address by remember { mutableStateOf(model.commaAddress) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        title = { Text("Live stream") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(address, { address = it }, singleLine = true, label = { Text("comma IP address") })
                Text("The comma must publish over ZeroMQ: run cereal's bridge on it. All of its buses are streamed.", fontSize = 12.sp)
                Button(onClick = { model.startComma(address); onClose() }) { Text("Connect to comma") }
                HorizontalDivider()
                Text("DashKit: plug its USB-C port into this phone.", fontSize = 12.sp)
                Button(onClick = { model.startDashKit(); onClose() }) { Text("Connect to DashKit over USB") }
            }
        },
    )
}

// ---- time cursor and A/B marks

@Composable
private fun TimeBar(model: AppModel) {
    model.revision
    val first = seconds(model.store.firstTime)
    val last = seconds(model.store.lastTime)
    if (model.store.frameCount == 0L || last <= first) return
    val cursor = seconds(model.cursorNow).coerceIn(first, last)
    Column(Modifier.padding(horizontal = 8.dp)) {
        Slider(cursor, { model.cursor = ticks(it); model.followLive = false }, valueRange = first..last)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("%.2f s".format(cursor), fontFamily = MONO)
            OutlinedButton(onClick = { model.markA = model.cursorNow }) { Text(model.markA?.let { "A %.2f".format(seconds(it)) } ?: "Mark A") }
            OutlinedButton(onClick = { model.markB = model.cursorNow }) { Text(model.markB?.let { "B %.2f".format(seconds(it)) } ?: "Mark B") }
            if (model.markA != null || model.markB != null) TextButton(onClick = { model.markA = null; model.markB = null }) { Text("Clear") }
            if (model.live != LiveSource.NONE && !model.followLive) TextButton(onClick = { model.followLive = true }) { Text("Now") }
        }
    }
}

// ---- one message: bits, signals, plots

@Composable
private fun MessageDetail(model: AppModel, message: FrameStore.Message, onBack: () -> Unit) {
    val revision = model.revision
    val store = model.store
    val decoder = model.decoder(message.bus, message.id)
    val signals = decoder?.signals.orEmpty()
    val plotted = remember(message.bus, message.id) { mutableStateListOf<String>() }
    var editing by remember { mutableStateOf<SignalDraft?>(null) }
    val cursor = model.cursorNow
    val following = model.followLive && model.live != LiveSource.NONE
    val frame = remember(revision, cursor, following) { if (following) message.data else store.frameAt(message, cursor) }
    val a = model.markA
    val b = model.markB
    val activity by produceState<FrameStore.BitActivity?>(null, revision, a, b, message) {
        val window = a != null && b != null
        value = withContext(Dispatchers.Default) {
            store.bitActivity(if (window) minOf(a!!, b!!) else Int.MIN_VALUE, if (window) maxOf(a!!, b!!) else Int.MAX_VALUE, message).getOrNull(message.index)
        }
    }
    val view = remember(message.bus, message.id) { ChartView(seconds(store.firstTime), seconds(store.lastTime)) }
    if (following) view.show(maxOf(seconds(store.lastTime) - LIVE_WINDOW_SECONDS, seconds(store.firstTime)), seconds(store.lastTime))
    val plottedNames = plotted.toList()
    val liveFrom = if (following) ticks(view.start) else Int.MIN_VALUE
    val series by produceState<Map<String, FrameStore.Series>>(emptyMap(), revision, plottedNames, decoder, liveFrom) {
        value = withContext(Dispatchers.Default) {
            plottedNames.mapNotNull { name -> signals.firstOrNull { it.name == name }?.let { name to store.series(message, decoder!!, it, liveFrom) } }.toMap()
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
                Text("${message.bus}  ${hexId(message.id)}  ${decoder?.name ?: ""}", fontFamily = MONO, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${message.count} frames, %.1f Hz".format(message.rate) + if (a != null && b != null) "  ·  green: changed only between A and B" else "  ·  brighter: changes more often",
                Modifier.padding(horizontal = 12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BitGrid(frame, activity, a != null && b != null, signals) { bit ->
                val owner = signals.firstOrNull { bit in it.bits }
                editing = if (owner != null) SignalDraft(owner) else SignalDraft(bit, signals.size)
            }
        }
        items(plottedNames, key = { "plot:$it" }) { name ->
            val index = signals.indexOfFirst { it.name == name }
            Chart(series[name], name, SIGNAL_COLORS[index.coerceAtLeast(0) % SIGNAL_COLORS.size], view, seconds(cursor), a?.let { seconds(it) }, b?.let { seconds(it) },
                onCursor = { model.cursor = ticks(it); model.followLive = false }, onMoved = { model.followLive = false })
        }
        item {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Signals", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = { editing = SignalDraft(0, signals.size) }) { Text("New signal") }
            }
            if (signals.isEmpty()) Text("No DBC entry for this message. Tap a bit to define a signal.", Modifier.padding(horizontal = 12.dp), fontSize = 12.sp)
        }
        items(signals.size, key = { "signal:$it" }) { index ->
            val signal = signals[index]
            val active = frame != null && signal.fits(frame.size) &&
                (signal.muxValue < 0 || decoder?.multiplexor?.let { it.fits(frame.size) && it.raw(frame) == signal.muxValue.toLong() } == true)
            Row(Modifier.fillMaxWidth().clickable { if (!plotted.remove(signal.name)) plotted += signal.name }.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(signal.name in plotted, onCheckedChange = null, Modifier.padding(12.dp))
                Box(Modifier.size(10.dp).background(SIGNAL_COLORS[index % SIGNAL_COLORS.size]))
                Text(signal.name, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (active) format(signal, signal.raw(frame!!)) else "–", fontFamily = MONO, fontSize = 13.sp)
                TextButton(onClick = { editing = SignalDraft(signal) }) { Text("Edit") }
            }
        }
    }
    editing?.let { draft ->
        SignalEditor(model, message, decoder, draft, frame, onClose = { editing = null })
    }
}

/** One row per payload byte, most significant bit on the left. Shade = how often the bit flips; outline = its DBC signal. */
@Composable
private fun BitGrid(frame: ByteArray?, activity: FrameStore.BitActivity?, window: Boolean, signals: List<Signal>, onBit: (Int) -> Unit) {
    val length = frame?.size ?: 0
    if (length == 0) { Text("No frame at the cursor yet", Modifier.padding(12.dp)); return }
    val busiest = activity?.inside?.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
        for (byte in 0 until length) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%2d".format(byte), Modifier.width(26.dp), fontFamily = MONO, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (bit in 7 downTo 0) {
                    val index = byte * 8 + bit
                    val inside = activity?.inside?.getOrNull(index) ?: 0
                    val outside = activity?.outside?.getOrNull(index) ?: 0
                    val heat = if (inside == 0) 0f else (0.25f + 0.75f * (Math.log1p(inside.toDouble()) / Math.log1p(busiest.toDouble())).toFloat())
                    val fill = when {
                        window && inside > 0 && outside == 0 -> Color(0xFF2E7D32)
                        inside > 0 -> Color(0xFF1565C0).copy(alpha = heat)
                        else -> Color(0xFF22262B)
                    }
                    val owner = signals.indexOfFirst { index in it.bits }
                    Box(
                        Modifier.padding(1.dp).size(32.dp).background(fill)
                            .border(2.dp, if (owner >= 0) SIGNAL_COLORS[owner % SIGNAL_COLORS.size] else Color.Transparent)
                            .clickable { onBit(index) },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (frame!![byte].toInt() shr bit and 1 == 1) "1" else "0", fontFamily = MONO, fontSize = 13.sp) }
                }
                Text("%02X".format(frame!![byte].toInt() and 0xFF), Modifier.padding(start = 8.dp), fontFamily = MONO)
            }
        }
    }
}

/** The fields of the signal editor, as text while they are being typed. */
private class SignalDraft(val original: String?, name: String, start: Int, size: Int, littleEndian: Boolean, signed: Boolean, factor: String, offset: String, unit: String, val source: Signal?) {
    constructor(s: Signal) : this(s.name, s.name, s.start, s.size, s.littleEndian, s.signed, s.factor.toString(), s.offset.toString(), s.unit, s)
    constructor(bit: Int, count: Int) : this(null, "SIGNAL_${count + 1}", bit, 1, true, false, "1", "0", "", null)

    var name by mutableStateOf(name)
    var start by mutableStateOf(start)
    var size by mutableStateOf(size)
    var littleEndian by mutableStateOf(littleEndian)
    var signed by mutableStateOf(signed)
    var factor by mutableStateOf(factor)
    var offset by mutableStateOf(offset)
    var unit by mutableStateOf(unit)

    /** The signal as entered, or null while a field is not valid. Multiplexing and value names of an edited signal are kept. */
    fun signal(): Signal? {
        if (!Regex("[A-Za-z_]\\w*").matches(name) || start !in 0 until FrameStore.MAX_PAYLOAD * 8 || size !in 1..64) return null
        val result = Signal(name, start, size, littleEndian, signed, factor.toDoubleOrNull() ?: return null, offset.toDoubleOrNull() ?: return null,
            unit.replace("\"", ""), source?.muxValue ?: -1, source?.isMultiplexor ?: false)
        if (result.bits.any { it !in 0 until FrameStore.MAX_PAYLOAD * 8 }) return null
        result.valueNames = source?.valueNames ?: emptyMap()
        return result
    }
}

@Composable
private fun SignalEditor(model: AppModel, message: FrameStore.Message, decoder: DbcMessage?, draft: SignalDraft, frame: ByteArray?, onClose: () -> Unit) {
    val signal = draft.signal()
    val revision = model.revision
    // the whole log decoded with the fields as they are now: change size or byte order and watch the shape
    val preview by produceState<FrameStore.Series?>(null, signal?.start, signal?.size, signal?.littleEndian, signal?.signed, revision) {
        value = signal?.let { s -> withContext(Dispatchers.Default) { model.store.series(message, DbcMessage(message.id, "", 0, listOf(s)), s) } }
    }
    val view = remember { ChartView(seconds(model.store.firstTime), seconds(model.store.lastTime)) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(enabled = signal != null, onClick = { model.saveSignal(message, signal!!, draft.original); onClose() }) { Text("Save") } },
        dismissButton = {
            Row {
                if (draft.original != null) TextButton(onClick = { model.deleteSignal(message, draft.original); onClose() }) { Text("Delete") }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
        title = { Text(if (draft.original == null) "New signal" else "Edit signal") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(draft.name, { draft.name = it }, singleLine = true, label = { Text("Name") })
                Stepper("Start bit", draft.start, 0, FrameStore.MAX_PAYLOAD * 8 - 1) { draft.start = it }
                Stepper("Size", draft.size, 1, 64) { draft.size = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(draft.littleEndian, { draft.littleEndian = it }); Text(if (draft.littleEndian) " Little-endian (start = lowest bit)" else " Big-endian (start = highest bit)", fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(draft.signed, { draft.signed = it }); Text(" Signed", fontSize = 12.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val numbers = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    OutlinedTextField(draft.factor, { draft.factor = it }, Modifier.width(84.dp), singleLine = true, label = { Text("Factor") }, keyboardOptions = numbers)
                    OutlinedTextField(draft.offset, { draft.offset = it }, Modifier.width(84.dp), singleLine = true, label = { Text("Offset") }, keyboardOptions = numbers)
                    OutlinedTextField(draft.unit, { draft.unit = it }, Modifier.width(84.dp), singleLine = true, label = { Text("Unit") })
                }
                Text(
                    when {
                        signal == null -> "Not a valid signal yet"
                        frame == null || !signal.fits(frame.size) -> "Doesn't fit in this frame's ${frame?.size ?: 0} bytes"
                        else -> "At the cursor: " + format(signal, signal.raw(frame))
                    }, fontFamily = MONO, fontSize = 13.sp,
                )
                Chart(preview, draft.name, SIGNAL_COLORS[0], view, seconds(model.cursorNow), null, null, onCursor = {}, onMoved = {})
            }
        },
    )
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(80.dp))
        OutlinedButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }) { Text("−") }
        Text(value.toString(), Modifier.width(48.dp).padding(horizontal = 8.dp), fontFamily = MONO)
        OutlinedButton(onClick = { onChange((value + 1).coerceAtMost(max)) }) { Text("+") }
        Spacer(Modifier.width(4.dp))
    }
}

const val LIVE_WINDOW_SECONDS = 30f
