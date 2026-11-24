package com.ehoeve.canpilot

import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehoeve.canpilot.core.Dbc
import com.ehoeve.canpilot.core.FrameStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- message list

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageList(model: AppModel, onFind: () -> Unit, onAuto: () -> Unit, onOpen: (FrameStore.Message) -> Unit) {
    var filter by remember { mutableStateOf("") }
    var changedOnly by remember { mutableStateOf(false) }
    var showDbcs by remember { mutableStateOf(false) }
    var showLive by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var openMenu by remember { mutableStateOf(false) }
    val mergeLogs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.mergeLogs(it) }
    var differentOnly by remember { mutableStateOf(false) }
    val openLogs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.openLogs(it) }
    val openOther = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.openCompare(it); differentOnly = it.isNotEmpty() }
    val revision = model.revision
    val a = model.trace.markA
    val b = model.trace.markB
    // bits that flipped between the marks and at no other time, for every message
    val activity by produceState<Array<FrameStore.BitActivity?>?>(null, changedOnly, a, b, revision) {
        value = if (changedOnly && a != null && b != null) withContext(Dispatchers.Default) { model.store.bitActivity(minOf(a, b), maxOf(a, b)) } else null
    }
    val dbcs = model.dbc.files.toList()
    val other = model.other
    val mine = model.mine
    // per message, the bits that are not the same in the other log
    val different = remember(other, mine, model.store.messages.size) {
        if (other == null || mine == null) null
        else IntArray(model.store.messages.size) { index -> model.store.messages[index].let { m -> mine.differences(other, m.bus, m.id, m.data.size * 8)?.count { it != 0 } ?: 0 } }
    }
    val rows = remember(revision, filter, dbcs, activity, different, differentOnly) {
        val needle = filter.trim().lowercase()
        val found = activity
        val differing = if (differentOnly) different else null
        model.store.messages.map { it to model.dbc.decoder(it.bus, it.id) }
            .filter { (m, d) -> needle.isEmpty() || needle in hexId(m.id).lowercase() || (d != null && (needle in d.name.lowercase() || d.signals.any { s -> needle in s.name.lowercase() })) }
            .filter { (m, _) -> found == null || (found.getOrNull(m.index)?.onlyInside ?: 0) > 0 }
            .filter { (m, _) -> differing == null || (differing.getOrNull(m.index) ?: 0) > 0 }
            .let { list ->
                when {
                    found != null -> list.sortedByDescending { found[it.first.index]!!.onlyInside }
                    differing != null -> list.sortedByDescending { differing.getOrNull(it.first.index) ?: 0 }
                    else -> list.sortedWith(compareBy({ it.first.bus }, { it.first.id }))
                }
            }
    }
    val live = model.live.source != LiveSource.NONE
    val empty = model.store.frameCount == 0L && !live && !model.busy
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("CanPilot", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (model.live.recording != null) Pill("REC", RECORDING, Color.White)
                    Spacer(Modifier.width(6.dp))
                    if (live) Pill("LIVE", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(12.dp))
                },
            )
        },
    ) { padding ->
        if (empty) {
            EmptyState(model.status, Modifier.padding(padding), onOpen = { openLogs.launch(arrayOf("*/*")) }, onLive = { showLive = true }, onDbc = { showDbcs = true }, dbcCount = dbcs.size, session = model.session.current, onSessions = { showSessions = true })
        } else Column(Modifier.padding(padding)) {
            // short labels, so that all of them fit in two rows without scrolling
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    AssistChip(onClick = { openMenu = true }, enabled = !model.busy, label = { Text("Open") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) })
                    DropdownMenu(openMenu, { openMenu = false }) {
                        DropdownMenuItem(text = { Text("Open logs") }, onClick = { openMenu = false; openLogs.launch(arrayOf("*/*")) })
                        if (!live) DropdownMenuItem(text = { Text("Add a log to this timeline") }, onClick = { openMenu = false; mergeLogs.launch(arrayOf("*/*")) })
                        if (other == null) DropdownMenuItem(text = { Text("Compare with a log") }, onClick = { openMenu = false; openOther.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text("Session: ${model.session.current}") }, onClick = { openMenu = false; showSessions = true })
                    }
                }
                if (live) {
                    AssistChip(onClick = { model.live.stop() }, label = { Text("Stop") }, leadingIcon = { Icon(Icons.Default.Close, null, Modifier.size(18.dp)) })
                    FilterChip(selected = model.live.recording != null, onClick = { model.live.setRecording(model.live.recording == null) }, label = { Text("Record") },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(RECORDING)) })
                } else AssistChip(onClick = { showLive = true }, label = { Text("Live") }, leadingIcon = { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { showDbcs = true }, label = { Text("DBC · ${dbcs.size}") })
                AssistChip(onClick = onFind, label = { Text("Find") }, leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) })
                AssistChip(onClick = onAuto, label = { Text("Decode") })
                FilterChip(selected = changedOnly, onClick = { changedOnly = !changedOnly }, label = { Text("Changed A–B") },
                    leadingIcon = if (changedOnly) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null)
                if (other != null) FilterChip(selected = differentOnly, onClick = { differentOnly = !differentOnly }, label = { Text("Differs from ${model.otherName}", maxLines = 1) },
                    trailingIcon = { Icon(Icons.Default.Close, "Stop comparing", Modifier.size(18.dp).clickable { model.clearCompare(); differentOnly = false }) })
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
                            val differ = if (differentOnly) different?.getOrNull(m.index) else null
                            if (onlyInside != null) Pill("$onlyInside bits", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                            else if (differ != null) Pill("$differ differ", DIFFERENT, Color.White)
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
    if (showSessions) SessionSheet(model) { showSessions = false }
}

@Composable
private fun EmptyState(status: String, modifier: Modifier, onOpen: () -> Unit, onLive: () -> Unit, onDbc: () -> Unit, dbcCount: Int, session: String, onSessions: () -> Unit) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Read a CAN bus", style = MaterialTheme.typography.headlineSmall)
        Hint("Open rlog, candump, ASC or CSV logs, or stream live from a comma device or a DashKit.", Modifier.padding(bottom = 8.dp))
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Open logs") }
        FilledTonalButton(onClick = onLive, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Live stream") }
        OutlinedButton(onClick = onDbc, modifier = Modifier.fillMaxWidth()) { Text(if (dbcCount == 0) "Add a DBC" else "DBC files · $dbcCount") }
        TextButton(onClick = onSessions) { Text("Session: $session") }
        Hint(status, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun DbcSheet(model: AppModel, onClose: () -> Unit) {
    // per bus: how many of the DBC's messages on it have the frame length the DBC gives them. A DBC for another bus
    // shares ids by chance and mostly gets the lengths wrong: assign it to the bus where it fits.
    val messages = model.store.messages
    val fits = { dbc: Dbc ->
        messages.filter { it.id in dbc.messages && it.bus < 128 }.groupBy { it.bus }.toSortedMap().entries.joinToString("") { (bus, onBus) ->
            "\nBus $bus: ${onBus.count { dbc.messages[it.id]!!.length == it.data.size } * 100 / onBus.size}% of ${onBus.size} fit"
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) model.dbc.open(it) }
    var exporting by remember { mutableStateOf(-1) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && exporting in model.dbc.files.indices) model.dbc.export(exporting, uri)
    }
    val buses = (listOf(0, 1, 2, 3) + model.store.messages.map { it.bus }).distinct().sorted()
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("DBC files", style = MaterialTheme.typography.titleLarge)
            if (model.dbc.files.isEmpty()) Hint("None yet. Add one for all buses, or one per bus.")
            model.dbc.files.forEachIndexed { index, loaded ->
                var menu by remember { mutableStateOf(false) }
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(loaded.dbc.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Hint("${loaded.dbc.messages.size} messages" + fits(loaded.dbc))
                        }
                        Box {
                            AssistChip(onClick = { menu = true }, label = { Text(if (loaded.bus == ALL_BUSES) "All buses" else "Bus ${loaded.bus}") })
                            DropdownMenu(menu, { menu = false }) {
                                for (bus in listOf(ALL_BUSES) + buses) {
                                    DropdownMenuItem(text = { Text(if (bus == ALL_BUSES) "All buses" else "Bus $bus") }, onClick = { model.dbc.setBus(index, bus); menu = false })
                                }
                            }
                        }
                        IconButton(onClick = { exporting = index; export.launch(loaded.dbc.name) }) { Icon(Icons.Default.Share, "Export ${loaded.dbc.name}") }
                        IconButton(onClick = { model.dbc.remove(index) }) { Icon(Icons.Default.Delete, "Remove ${loaded.dbc.name}") }
                    }
                }
            }
            Button(onClick = { open.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add DBC") }
        }
    }
}

@Composable
private fun LiveSheet(model: AppModel, onClose: () -> Unit) {
    var address by remember { mutableStateOf(model.live.commaAddress) }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Live stream", style = MaterialTheme.typography.titleLarge)
            SectionTitle("comma over Wi-Fi")
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("IP address") },
                placeholder = { Text("Found automatically") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Hint("Can stay empty: the app finds the comma on this phone's Wi-Fi or hotspot, also after its address changed. The comma must run cereal's bridge. All of its buses are streamed.")
            Button(onClick = { model.live.startComma(address); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Connect to comma") }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SectionTitle("DashKit over USB")
            Hint("Plug the DashKit's USB-C port into this phone.")
            FilledTonalButton(onClick = { model.live.startDashKit(); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Connect to DashKit") }
        }
    }
}

// ---- time cursor and A/B marks

@Composable
fun TimeBar(model: AppModel, modifier: Modifier = Modifier) {
    model.revision
    val first = seconds(model.store.firstTime)
    val last = seconds(model.store.lastTime)
    if (model.store.frameCount == 0L || last <= first) return
    val cursor = seconds(model.trace.cursorNow).coerceIn(first, last)
    var pending by remember { mutableStateOf<PendingMarker?>(null) }
    var showMarkers by remember { mutableStateOf(false) }
    pending?.let { marker -> MarkerDialog(model, marker) { pending = null } }
    if (showMarkers) MarkerSheet(model) { showMarkers = false }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("%.2f s".format(cursor), Modifier.width(92.dp), fontFamily = MONO, style = MaterialTheme.typography.titleMedium)
                FilterChip(selected = model.trace.markA != null, onClick = { model.trace.markA = model.trace.cursorNow }, label = { Text(model.trace.markA?.let { "A  %.2f".format(seconds(it)) } ?: "Mark A") })
                FilterChip(selected = model.trace.markB != null, onClick = { model.trace.markB = model.trace.cursorNow }, label = { Text(model.trace.markB?.let { "B  %.2f".format(seconds(it)) } ?: "Mark B") })
                if (model.trace.markA != null || model.trace.markB != null) IconButton(onClick = { model.trace.markA = null; model.trace.markB = null }) { Icon(Icons.Default.Close, "Clear marks") }
                if (model.live.source != LiveSource.NONE && !model.trace.followLive) AssistChip(onClick = { model.trace.followLive = true }, label = { Text("Now") })
                AssistChip(onClick = { pending = model.beginMarker() }, label = { Text("Marker") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) })
                if (model.live.source != LiveSource.NONE) FilterChip(selected = model.live.recording != null, onClick = { model.live.setRecording(model.live.recording == null) }, label = { Text("Record") },
                    leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(RECORDING)) })
                if (model.trace.markers.isNotEmpty()) AssistChip(onClick = { showMarkers = true }, label = { Text("Markers · ${model.trace.markers.size}") })
            }
            Slider(cursor, { model.trace.cursor = ticks(it); model.trace.followLive = false }, valueRange = first..last)
        }
    }
}
