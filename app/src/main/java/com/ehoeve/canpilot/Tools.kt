package com.ehoeve.canpilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Button
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

/** Finds where a signal lives: by what its value was at known moments, or by a known signal it moves with. */
@Composable
fun FindScreen(model: AppModel, onBack: () -> Unit, onPick: (FieldMatch) -> Unit) {
    var tolerance by remember { mutableStateOf("1") }
    val results = model.findResults
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Find a signal", style = MaterialTheme.typography.titleMedium) },
            )
        },
        bottomBar = { TimeBar(model) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                SectionTitle("By its value")
                Hint("Move the cursor to a moment when you know what the value was, add a point and type the value. Points with different values narrow it down; three or more also find an offset.")
            }
            items(model.findPoints.size) { index ->
                val point = model.findPoints[index]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%.2f s".format(seconds(point.time)), Modifier.width(96.dp), fontFamily = MONO)
                    OutlinedTextField(point.value, { point.value = it }, Modifier.weight(1f), singleLine = true, label = { Text("Value then") }, keyboardOptions = numbers)
                    IconButton(onClick = { model.findPoints.removeAt(index) }) { Icon(Icons.Default.Delete, "Remove point") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { model.findPoints += FindPoint(model.cursorNow) }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Point at cursor") }
                    OutlinedTextField(tolerance, { tolerance = it }, Modifier.width(88.dp), singleLine = true, label = { Text("±") }, keyboardOptions = numbers)
                    Button(enabled = !model.searching && model.findPoints.isNotEmpty(), onClick = { model.searchByValue(tolerance.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0) }) {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Search")
                    }
                }
                HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 6.dp))
                SectionTitle("By a signal it moves with")
                if (model.plots.isEmpty()) Hint("Plot a known signal first: open its message and tick it. It then appears here.")
                else Hint("Lists the fields in other messages that rise and fall with a plotted signal, scaled to its units.")
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (key in model.plots.toList()) {
                            AssistChip(enabled = !model.searching, onClick = { model.searchCorrelated(key) }, label = { Text("${hexId(key.id)} · ${key.signal}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) })
                        }
                    }
                }
                if (model.findStatus.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Hint(model.findStatus)
                }
                if (model.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
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
                                Text(model.decoder(match.message.bus, match.message.id)?.name ?: "", Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            Hint("start ${signal.start} · ${signal.size} bits · ${if (signal.littleEndian) "little" else "big"}-endian${if (signal.signed) " · signed" else ""} · ×${"%.4g".format(signal.factor)}" +
                                if (signal.offset != 0.0) " ${"%+.4g".format(signal.offset)}" else "")
                        }
                        Pill(if (model.findByCorrelation) "r %.3f".format(match.score) else "±%.2g".format(match.score))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

/** Asks for the note of a marker whose moment was already taken. Tapping outside keeps the marker with its default note. */
@Composable
fun MarkerDialog(model: AppModel, pending: PendingMarker, onClose: () -> Unit) {
    val default = "Marker ${model.markers.size + 1}"
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
            model.markers.toList().forEach { marker ->
                Surface(onClick = { model.cursor = marker.time; model.followLive = false; onClose() }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("%.2f s".format(seconds(marker.time)), Modifier.width(88.dp), fontFamily = MONO)
                        Text(marker.note, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            model.markA = (marker.time - FrameStore.TICKS_PER_SECOND.toInt()).coerceAtLeast(model.store.firstTime)
                            model.markB = (marker.time + 2 * FrameStore.TICKS_PER_SECOND.toInt()).coerceAtMost(model.store.lastTime)
                        }) { Text("A–B") }
                        IconButton(onClick = { model.markers.remove(marker) }) { Icon(Icons.Default.Delete, "Remove marker") }
                    }
                }
            }
        }
    }
}
