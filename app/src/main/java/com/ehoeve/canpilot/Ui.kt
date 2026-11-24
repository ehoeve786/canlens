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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ehoeve.canpilot.core.DbcMessage
import com.ehoeve.canpilot.core.FrameStore
import com.ehoeve.canpilot.core.Signal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val MONO = FontFamily.Monospace

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
    CanPilotTheme {
        if (message == null) MessageList(model) { selected = it.bus to it.id }
        else {
            BackHandler { selected = null }
            MessageDetail(model, message) { selected = null }
        }
    }
}

// ---- small pieces

@Composable
private fun BusBadge(bus: Int) {
    val color = BUS_COLORS[bus.coerceAtLeast(0) % BUS_COLORS.size]
    Box(Modifier.size(30.dp).clip(MaterialTheme.shapes.extraSmall).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Text(bus.toString(), color = color, fontFamily = MONO, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Pill(text: String, color: Color = MaterialTheme.colorScheme.surfaceContainerHighest, content: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(shape = CircleShape, color = color, contentColor = content) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
    val live = model.live != LiveSource.NONE
    val empty = model.store.frameCount == 0L && !live && !model.busy
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CanPilot", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (model.recording != null) Pill("REC", RECORDING, Color.White)
                    Spacer(Modifier.width(6.dp))
                    if (live) Pill("LIVE", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(12.dp))
                },
            )
        },
        bottomBar = { TimeBar(model) },
    ) { padding ->
        if (empty) {
            EmptyState(model.status, Modifier.padding(padding), onOpen = { openLogs.launch(arrayOf("*/*")) }, onLive = { showLive = true }, onDbc = { showDbcs = true }, dbcCount = dbcs.size)
        } else Column(Modifier.padding(padding)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = { openLogs.launch(arrayOf("*/*")) }, enabled = !model.busy, label = { Text("Open logs") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) })
                if (live) {
                    AssistChip(onClick = { model.stopLive() }, label = { Text("Stop") }, leadingIcon = { Icon(Icons.Default.Close, null, Modifier.size(18.dp)) })
                    FilterChip(selected = model.recording != null, onClick = { model.setRecording(model.recording == null) }, label = { Text("Record") },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(RECORDING)) })
                } else AssistChip(onClick = { showLive = true }, label = { Text("Live") }, leadingIcon = { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { showDbcs = true }, label = { Text("DBC · ${dbcs.size}") })
                FilterChip(selected = changedOnly, onClick = { changedOnly = !changedOnly }, label = { Text("Changed only in A–B") },
                    leadingIcon = if (changedOnly) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null)
            }
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    Hint(model.status, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (changedOnly && (a == null || b == null)) {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("Set marks A and B around the moment you did something in the car. This lists the messages with bits that changed then and at no other time.",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(
                filter, { filter = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true, shape = CircleShape,
                placeholder = { Text("Filter by id, message or signal") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = if (filter.isEmpty()) null else ({ IconButton(onClick = { filter = "" }) { Icon(Icons.Default.Close, "Clear filter") } }),
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { it.first.index }) { (m, decoder) ->
                    Surface(onClick = { onOpen(m) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            BusBadge(m.bus)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(hexId(m.id), fontFamily = MONO, fontWeight = FontWeight.Bold)
                                    Text(decoder?.name ?: "", Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(hexBytes(m.data), fontFamily = MONO, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val onlyInside = activity?.getOrNull(m.index)?.onlyInside
                            if (onlyInside != null) Pill("$onlyInside bits", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                            else Pill("%.0f Hz".format(m.rate))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
    if (showDbcs) DbcSheet(model) { showDbcs = false }
    if (showLive) LiveSheet(model) { showLive = false }
}

@Composable
private fun EmptyState(status: String, modifier: Modifier, onOpen: () -> Unit, onLive: () -> Unit, onDbc: () -> Unit, dbcCount: Int) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Read a CAN bus", style = MaterialTheme.typography.headlineSmall)
        Hint("Open rlog, candump, ASC or CSV logs, or stream live from a comma device or a DashKit.", Modifier.padding(bottom = 8.dp))
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Open logs") }
        FilledTonalButton(onClick = onLive, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Live stream") }
        OutlinedButton(onClick = onDbc, modifier = Modifier.fillMaxWidth()) { Text(if (dbcCount == 0) "Add a DBC" else "DBC files · $dbcCount") }
        Hint(status, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun DbcSheet(model: AppModel, onClose: () -> Unit) {
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) model.openDbc(it) }
    var exporting by remember { mutableStateOf(-1) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && exporting in model.dbcs.indices) model.exportDbc(exporting, uri)
    }
    val buses = (listOf(0, 1, 2, 3) + model.store.messages.map { it.bus }).distinct().sorted()
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("DBC files", style = MaterialTheme.typography.titleLarge)
            if (model.dbcs.isEmpty()) Hint("None yet. Add one for all buses, or one per bus.")
            model.dbcs.forEachIndexed { index, loaded ->
                var menu by remember { mutableStateOf(false) }
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(loaded.dbc.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Hint("${loaded.dbc.messages.size} messages")
                        }
                        Box {
                            AssistChip(onClick = { menu = true }, label = { Text(if (loaded.bus == ALL_BUSES) "All buses" else "Bus ${loaded.bus}") })
                            DropdownMenu(menu, { menu = false }) {
                                for (bus in listOf(ALL_BUSES) + buses) {
                                    DropdownMenuItem(text = { Text(if (bus == ALL_BUSES) "All buses" else "Bus $bus") }, onClick = { model.setDbcBus(index, bus); menu = false })
                                }
                            }
                        }
                        IconButton(onClick = { exporting = index; export.launch(loaded.dbc.name) }) { Icon(Icons.Default.Share, "Export ${loaded.dbc.name}") }
                        IconButton(onClick = { model.removeDbc(index) }) { Icon(Icons.Default.Delete, "Remove ${loaded.dbc.name}") }
                    }
                }
            }
            Button(onClick = { open.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add DBC") }
        }
    }
}

@Composable
private fun LiveSheet(model: AppModel, onClose: () -> Unit) {
    var address by remember { mutableStateOf(model.commaAddress) }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Live stream", style = MaterialTheme.typography.titleLarge)
            SectionTitle("comma over Wi-Fi")
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("IP address") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Hint("The comma must publish over ZeroMQ: run cereal's bridge on it. All of its buses are streamed.")
            Button(onClick = { model.startComma(address); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Connect to comma") }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SectionTitle("DashKit over USB")
            Hint("Plug the DashKit's USB-C port into this phone.")
            FilledTonalButton(onClick = { model.startDashKit(); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Connect to DashKit") }
        }
    }
}

// ---- time cursor and A/B marks

@Composable
private fun TimeBar(model: AppModel) {
    model.revision
    val first = seconds(model.store.firstTime)
    val last = seconds(model.store.lastTime)
    if (model.store.frameCount == 0L || last <= first) return
    val cursor = seconds(model.cursorNow).coerceIn(first, last)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("%.2f s".format(cursor), Modifier.width(92.dp), fontFamily = MONO, style = MaterialTheme.typography.titleMedium)
                FilterChip(selected = model.markA != null, onClick = { model.markA = model.cursorNow }, label = { Text(model.markA?.let { "A  %.2f".format(seconds(it)) } ?: "Mark A") })
                FilterChip(selected = model.markB != null, onClick = { model.markB = model.cursorNow }, label = { Text(model.markB?.let { "B  %.2f".format(seconds(it)) } ?: "Mark B") })
                if (model.markA != null || model.markB != null) IconButton(onClick = { model.markA = null; model.markB = null }) { Icon(Icons.Default.Close, "Clear marks") }
                if (model.live != LiveSource.NONE && !model.followLive) AssistChip(onClick = { model.followLive = true }, label = { Text("Now") })
            }
            Slider(cursor, { model.cursor = ticks(it); model.followLive = false }, valueRange = first..last)
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
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(decoder?.name ?: "Message ${hexId(message.id)}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Hint("bus ${message.bus} · ${hexId(message.id)} · ${message.count} frames · ${"%.1f".format(message.rate)} Hz")
                    }
                },
            )
        },
        bottomBar = { TimeBar(model) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        SectionTitle("Bits", Modifier.padding(horizontal = 14.dp))
                        Hint(if (a != null && b != null) "Green: changed only between A and B. Tap a bit to define a signal." else "Brighter: changes more often. Tap a bit to define a signal.",
                            Modifier.padding(horizontal = 14.dp))
                        BitGrid(frame, activity, a != null && b != null, signals) { bit ->
                            val owner = signals.firstOrNull { bit in it.bits }
                            editing = if (owner != null) SignalDraft(owner) else SignalDraft(bit, signals.size)
                        }
                    }
                }
            }
            items(plottedNames, key = { "plot:$it" }) { name ->
                val index = signals.indexOfFirst { it.name == name }
                Chart(series[name], name, SIGNAL_COLORS[index.coerceAtLeast(0) % SIGNAL_COLORS.size], view, seconds(cursor), a?.let { seconds(it) }, b?.let { seconds(it) },
                    onCursor = { model.cursor = ticks(it); model.followLive = false }, onMoved = { model.followLive = false })
            }
            item {
                Row(Modifier.padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Signals", Modifier.weight(1f))
                    TextButton(onClick = { editing = SignalDraft(0, signals.size) }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("New signal") }
                }
                if (signals.isEmpty()) Hint("No DBC entry for this message. Tap a bit to define a signal.", Modifier.padding(horizontal = 4.dp))
            }
            items(signals.size, key = { "signal:$it" }) { index ->
                val signal = signals[index]
                val active = frame != null && signal.fits(frame.size) &&
                    (signal.muxValue < 0 || decoder?.multiplexor?.let { it.fits(frame.size) && it.raw(frame) == signal.muxValue.toLong() } == true)
                Surface(onClick = { if (!plotted.remove(signal.name)) plotted += signal.name }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(signal.name in plotted, onCheckedChange = null)
                        Box(Modifier.padding(start = 10.dp).size(10.dp).clip(CircleShape).background(SIGNAL_COLORS[index % SIGNAL_COLORS.size]))
                        Text(signal.name, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(if (active) format(signal, signal.raw(frame!!)) else "–", fontFamily = MONO, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        IconButton(onClick = { editing = SignalDraft(signal) }) { Icon(Icons.Default.Edit, "Edit ${signal.name}", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
    editing?.let { draft ->
        SignalEditor(model, message, draft, frame, onClose = { editing = null })
    }
}

/** One row per payload byte, most significant bit on the left. Shade = how often the bit flips; outline = its DBC signal. */
@Composable
private fun BitGrid(frame: ByteArray?, activity: FrameStore.BitActivity?, window: Boolean, signals: List<Signal>, onBit: (Int) -> Unit) {
    if (frame == null || frame.isEmpty()) { Hint("No frame at the cursor yet", Modifier.padding(14.dp)); return }
    val busiest = activity?.inside?.maxOrNull()?.coerceAtLeast(1) ?: 1
    val cell = MaterialTheme.shapes.extraSmall
    Column(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(top = 8.dp)) {
        for (byte in frame.indices) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(byte.toString(), Modifier.width(24.dp), fontFamily = MONO, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (bit in 7 downTo 0) {
                    val index = byte * 8 + bit
                    val inside = activity?.inside?.getOrNull(index) ?: 0
                    val outside = activity?.outside?.getOrNull(index) ?: 0
                    val heat = if (inside == 0) 0f else (0.25f + 0.75f * (Math.log1p(inside.toDouble()) / Math.log1p(busiest.toDouble())).toFloat())
                    val fill = when {
                        window && inside > 0 && outside == 0 -> Color(0xFF2E7D32)
                        inside > 0 -> Color(0xFF1976D2).copy(alpha = heat)
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                    val owner = signals.indexOfFirst { index in it.bits }
                    Box(
                        Modifier.padding(1.5.dp).size(33.dp).clip(cell).background(fill)
                            .border(2.dp, if (owner >= 0) SIGNAL_COLORS[owner % SIGNAL_COLORS.size] else Color.Transparent, cell)
                            .clickable { onBit(index) },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (frame[byte].toInt() shr bit and 1 == 1) "1" else "0", fontFamily = MONO, style = MaterialTheme.typography.bodyMedium) }
                }
                Text("%02X".format(frame[byte].toInt() and 0xFF), Modifier.padding(start = 10.dp), fontFamily = MONO, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun SignalEditor(model: AppModel, message: FrameStore.Message, draft: SignalDraft, frame: ByteArray?, onClose: () -> Unit) {
    val signal = draft.signal()
    val revision = model.revision
    // the whole log decoded with the fields as they are now: change size or byte order and watch the shape
    val preview by produceState<FrameStore.Series?>(null, signal?.start, signal?.size, signal?.littleEndian, signal?.signed, revision) {
        value = signal?.let { s -> withContext(Dispatchers.Default) { model.store.series(message, DbcMessage(message.id, "", 0, listOf(s)), s) } }
    }
    val view = remember { ChartView(seconds(model.store.firstTime), seconds(model.store.lastTime)) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { Button(enabled = signal != null, onClick = { model.saveSignal(message, signal!!, draft.original); onClose() }) { Text("Save") } },
        dismissButton = {
            Row {
                if (draft.original != null) TextButton(onClick = { model.deleteSignal(message, draft.original); onClose() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
        title = { Text(if (draft.original == null) "New signal" else "Edit signal") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.name, { draft.name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
                Stepper("Start bit", draft.start, 0, FrameStore.MAX_PAYLOAD * 8 - 1) { draft.start = it }
                Stepper("Size", draft.size, 1, 64) { draft.size = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(draft.littleEndian, { draft.littleEndian = it })
                    Hint(if (draft.littleEndian) "Little-endian (start = lowest bit)" else "Big-endian (start = highest bit)", Modifier.padding(start = 10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(draft.signed, { draft.signed = it }); Hint("Signed", Modifier.padding(start = 10.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val numbers = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    OutlinedTextField(draft.factor, { draft.factor = it }, Modifier.weight(1f), singleLine = true, label = { Text("Factor") }, keyboardOptions = numbers)
                    OutlinedTextField(draft.offset, { draft.offset = it }, Modifier.weight(1f), singleLine = true, label = { Text("Offset") }, keyboardOptions = numbers)
                    OutlinedTextField(draft.unit, { draft.unit = it }, Modifier.weight(1f), singleLine = true, label = { Text("Unit") })
                }
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text(
                        when {
                            signal == null -> "Not a valid signal yet"
                            frame == null || !signal.fits(frame.size) -> "Doesn't fit in this frame's ${frame?.size ?: 0} bytes"
                            else -> "At the cursor: " + format(signal, signal.raw(frame))
                        }, Modifier.fillMaxWidth().padding(10.dp), fontFamily = MONO, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Chart(preview, draft.name, SIGNAL_COLORS[0], view, seconds(model.cursorNow), null, null, onCursor = {}, onMoved = {})
            }
        },
    )
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        FilledTonalIconButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }) { Text("−") }
        Text(value.toString(), Modifier.width(56.dp), fontFamily = MONO, textAlign = TextAlign.Center)
        FilledTonalIconButton(onClick = { onChange((value + 1).coerceAtMost(max)) }) { Text("+") }
    }
}

const val LIVE_WINDOW_SECONDS = 30f
