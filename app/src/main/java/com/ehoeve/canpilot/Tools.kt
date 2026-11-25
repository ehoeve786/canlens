package com.ehoeve.canpilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehoeve.canpilot.core.FieldMatch
import com.ehoeve.canpilot.core.FrameStore
import com.ehoeve.canpilot.core.Suggestion

/** Finds where a signal lives: by what its value was at known moments, or by a known signal it moves with. */
@Composable
fun FindScreen(model: AppModel, onBack: () -> Unit, onPick: (FieldMatch) -> Unit) {
    var tolerance by remember { mutableStateOf("1") }
    val results = model.signals.findResults
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Find a signal", style = MaterialTheme.typography.titleMedium) },
            )
        },
        bottomBar = { TimeBar(model, Modifier.navigationBarsPadding()) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                SectionTitle("By its value")
                Hint("Move the cursor to a moment when you know what the value was, add a point and type the value. Points with different values narrow it down; three or more also find an offset.")
            }
            items(model.signals.findPoints.size) { index ->
                val point = model.signals.findPoints[index]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%.2f s".format(seconds(point.time)), Modifier.width(96.dp), fontFamily = MONO)
                    OutlinedTextField(point.value, { point.value = it }, Modifier.weight(1f), singleLine = true, label = { Text("Value then") }, keyboardOptions = numbers)
                    IconButton(onClick = { model.signals.findPoints.removeAt(index) }) { Icon(Icons.Default.Delete, "Remove point") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { model.signals.findPoints += FindPoint(model.trace.cursorNow) }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Point at cursor") }
                    OutlinedTextField(tolerance, { tolerance = it }, Modifier.width(88.dp), singleLine = true, label = { Text("±") }, keyboardOptions = numbers)
                    Button(enabled = !model.signals.searching && model.signals.findPoints.isNotEmpty(), onClick = { model.signals.searchByValue(tolerance.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0) }) {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Search")
                    }
                }
                HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 6.dp))
                SectionTitle("By a signal it moves with")
                if (model.plot.keys.isEmpty()) Hint("Plot a known signal first: open its message and tick it. It then appears here.")
                else Hint("Lists the fields in other messages that rise and fall with a plotted signal, scaled to its units.")
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (key in model.plot.keys.toList()) {
                            AssistChip(enabled = !model.signals.searching, onClick = { model.signals.searchCorrelated(key) }, label = { Text("${hexId(key.id)} · ${key.signal}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) })
                        }
                    }
                }
                if (model.signals.findStatus.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Hint(model.signals.findStatus)
                }
                if (model.signals.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            items(results.size) { index ->
                val match = results[index]
                val signal = match.signal
                Surface(onClick = { onPick(match) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BusBadge(match.message.bus)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(hexId(match.message.id), fontFamily = MONO, fontWeight = FontWeight.Bold)
                                Text(model.dbc.decoder(match.message.bus, match.message.id)?.name ?: "", Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            Hint("start ${signal.start} · ${signal.size} bits · ${if (signal.littleEndian) "little" else "big"}-endian${if (signal.signed) " · signed" else ""} · ×${"%.4g".format(signal.factor)}" +
                                if (signal.offset != 0.0) " ${"%+.4g".format(signal.offset)}" else "")
                        }
                        Pill(if (model.signals.findByCorrelation) "r %.3f".format(match.score) else "±%.2g".format(match.score))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

/** Asks for the note of a marker whose moment was already taken. Tapping outside keeps the marker with its default note. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarkerDialog(model: AppModel, pending: PendingMarker, onClose: () -> Unit) {
    val default = "Marker ${model.trace.markers.size + 1}"
    var note by remember { mutableStateOf("") }
    fun save() { model.addMarker(pending, note.trim().ifEmpty { default }); onClose() }
    AlertDialog(
        onDismissRequest = { save() },
        confirmButton = { Button(onClick = { save() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Discard") } },
        title = { Text("Marker at %.2f s".format(seconds(pending.time))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("What happened") }, placeholder = { Text(default) })
                // one tap saves the marker with a note used before
                if (model.recentNotes.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.recentNotes.forEach { recent -> AssistChip(onClick = { model.addMarker(pending, recent); onClose() }, label = { Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                }
                if (pending.fileMicros != null) Hint("Written into the recording too.")
            }
        },
    )
}

@Composable
fun MarkerSheet(model: AppModel, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Markers", style = MaterialTheme.typography.titleLarge)
            Hint("Tap one to move the cursor there. A–B puts the marks from 1 s before to 2 s after it.")
            model.trace.markers.toList().forEach { marker ->
                Surface(onClick = { model.trace.cursor = marker.time; model.trace.followLive = false; onClose() }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("%.2f s".format(seconds(marker.time)), Modifier.width(88.dp), fontFamily = MONO)
                        Text(marker.note, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            model.trace.markA = (marker.time - FrameStore.TICKS_PER_SECOND.toInt()).coerceAtLeast(model.store.firstTime)
                            model.trace.markB = (marker.time + 2 * FrameStore.TICKS_PER_SECOND.toInt()).coerceAtMost(model.store.lastTime)
                        }) { Text("A–B") }
                        IconButton(onClick = { model.trace.markers.remove(marker) }) { Icon(Icons.Default.Delete, "Remove marker") }
                    }
                }
            }
        }
    }
}

/** Runs the automatic decoder and lists what it proposes, to be reviewed and added to the DBC. */
@Composable
fun AutoDecodeScreen(model: AppModel, onBack: () -> Unit, onPick: (Suggestion) -> Unit) {
    var showPlain by remember { mutableStateOf(false) }
    val all = model.signals.autoResults
    // named or verified ones start ticked; plain layout guesses only when ticked by hand
    val chosen = remember(all) { mutableStateListOf<Suggestion>().apply { addAll(all.filter { it.confidence >= SignalState.NAMED }) } }
    val plain = all.count { it.confidence < SignalState.NAMED }
    val shown = if (showPlain) all else all.filter { it.confidence >= SignalState.NAMED }
    val ticked = shown.filter { it in chosen }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Decode everything", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Hint("Works out the layout of every message that has no DBC entry: fields, counters, checksums, multiplexing. Fields that follow a signal you have already decoded, " +
                    "or that changed only at one of your markers, get a name. These are suggestions: check them before you rely on them.")
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !model.signals.decoding && model.store.frameCount > 0, onClick = { model.signals.decodeEverything() }) { Text(if (all.isEmpty()) "Decode" else "Decode again") }
                    if (ticked.isNotEmpty()) OutlinedButton(enabled = !model.signals.decoding, onClick = { model.signals.accept(ticked) }) { Text("Add ${ticked.size} to the DBC") }
                }
                if (model.signals.autoStatus.isNotEmpty()) Hint(model.signals.autoStatus, Modifier.padding(top = 8.dp))
                if (model.signals.decoding) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                if (plain > 0) FilterChip(selected = showPlain, onClick = { showPlain = !showPlain }, label = { Text("Show the $plain unnamed fields") })
            }
            items(shown.size) { index ->
                val suggestion = shown[index]
                val signal = suggestion.signal
                Surface(onClick = { onPick(suggestion) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(suggestion in chosen, onCheckedChange = { if (it) chosen += suggestion else chosen.remove(suggestion) })
                        BusBadge(suggestion.message.bus)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(hexId(suggestion.message.id), fontFamily = MONO, fontWeight = FontWeight.Bold)
                                Text(signal.name, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            }
                            Hint("bits ${signal.start}–${signal.start + signal.size - 1}" + (if (signal.muxValue >= 0) " · when the multiplexor is ${signal.muxValue}" else "") + " · " + suggestion.evidence)
                        }
                        Pill("${(suggestion.confidence * 100).toInt()}%")
                    }
                }
            }
        }
    }
}
