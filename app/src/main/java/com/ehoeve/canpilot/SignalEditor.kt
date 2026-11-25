package com.ehoeve.canpilot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehoeve.canpilot.core.Analysis
import com.ehoeve.canpilot.core.DbcMessage
import com.ehoeve.canpilot.core.FrameStore
import com.ehoeve.canpilot.core.Signal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The fields of the signal editor, as text while they are being typed. [original] is the name of the signal being edited, null for a new one. */
class SignalDraft(val original: String?, from: Signal) {
    var name by mutableStateOf(from.name)
    var start by mutableStateOf(from.start)
    var size by mutableStateOf(from.size)
    var littleEndian by mutableStateOf(from.littleEndian)
    var signed by mutableStateOf(from.signed)
    var factor by mutableStateOf(tidy(from.factor))
    var offset by mutableStateOf(tidy(from.offset))
    var unit by mutableStateOf(from.unit)
    var valueNames by mutableStateOf(from.valueNames.entries.joinToString(", ") { "${it.key}=${it.value}" })
    var isMultiplexor by mutableStateOf(from.isMultiplexor)
    var muxValue by mutableStateOf(if (from.muxValue >= 0) from.muxValue.toString() else "")

    /** The signal as entered, or null while a field is not valid. */
    fun signal(): Signal? {
        if (!Regex("[A-Za-z_]\\w*").matches(name) || start !in 0 until FrameStore.MAX_PAYLOAD * 8 || size !in 1..64) return null
        val mux = if (isMultiplexor || muxValue.isBlank()) -1 else muxValue.trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
        // "0=PARK, 1=DRIVE"
        val names = LinkedHashMap<Long, String>()
        for (entry in valueNames.split(',', '\n')) {
            if (entry.isBlank()) continue
            val value = entry.substringBefore('=').trim().toLongOrNull() ?: return null
            val label = entry.substringAfter('=', "").trim().replace("\"", "")
            if (label.isEmpty()) return null
            names[value] = label
        }
        val result = Signal(name, start, size, littleEndian, signed, factor.toDoubleOrNull() ?: return null, offset.toDoubleOrNull() ?: return null,
            unit.replace("\"", ""), mux, isMultiplexor)
        if (result.bits.any { it !in 0 until FrameStore.MAX_PAYLOAD * 8 }) return null
        result.valueNames = names
        return result
    }

    companion object {
        /** Six significant digits: a factor found by a search is a division result with noise in its last places. */
        fun tidy(value: Double): String =
            if (value.isNaN() || value.isInfinite()) "0" else java.math.BigDecimal(value).round(java.math.MathContext(6)).stripTrailingZeros().toPlainString()
    }
}

@Composable
fun SignalEditor(model: AppModel, message: FrameStore.Message, multiplexor: Signal?, draft: SignalDraft, frame: ByteArray?, onClose: () -> Unit) {
    val signal = draft.signal()
    val revision = model.revision
    // the whole log decoded with the fields as they are now: change size or byte order and watch the shape
    val preview by produceState<FrameStore.Series?>(null, signal?.start, signal?.size, signal?.littleEndian, signal?.signed, signal?.muxValue, revision) {
        value = signal?.let { s ->
            // a multiplexed signal is only read from the frames that carry it
            val selector = multiplexor?.takeIf { s.muxValue >= 0 && it.name != s.name }
            withContext(Dispatchers.Default) { model.store.series(message, DbcMessage(message.id, "", 0, listOfNotNull(s, selector)), s) }
        }
    }
    val histogram = remember(preview) { preview?.let { Analysis.histogram(it) } ?: emptyList() }
    val view = remember { ChartView(seconds(model.store.firstTime), seconds(model.store.lastTime)) }
    Column(Modifier.padding(horizontal = 14.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(if (draft.original == null) "New signal" else "Edit signal", Modifier.weight(1f))
            if (draft.original != null) TextButton(onClick = { model.dbc.deleteSignal(message, draft.original); onClose() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onClose) { Text("Cancel") }
            // saves this one and opens a copy for the next: the number in the name and the multiplexor page go up by one
            TextButton(enabled = signal != null, onClick = {
                model.dbc.saveSignal(message, signal!!, draft.original)
                val taken = model.dbc.decoder(message.bus, message.id)?.signals.orEmpty().mapTo(HashSet()) { it.name }
                val stem = signal.name.trimEnd { it.isDigit() }
                var number = signal.name.substring(stem.length).toIntOrNull() ?: 1
                var name: String
                do name = stem + ++number while (name in taken)
                model.signals.drafts[message.bus to message.id] = SignalDraft(null, signal).also {
                    it.name = name
                    if (signal.muxValue >= 0) it.muxValue = (signal.muxValue + 1).toString()
                }
            }) { Text("Copy") }
            Button(enabled = signal != null, onClick = { model.dbc.saveSignal(message, signal!!, draft.original); onClose() }) { Text("Save") }
        }
        OutlinedTextField(draft.name, { draft.name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
        // how sure it is, saved at once with the DBC; tapping the chosen one again clears it
        if (draft.original != null) {
            val status = model.dbc.status(message.bus, message.id, draft.original)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((value, label) in listOf("guess" to "Guess ?", "plotted" to "Plotted ~", "confirmed" to "Confirmed ✓"))
                    FilterChip(status == value, { model.dbc.setStatus(message, draft.original, value.takeIf { it != status }) }, { Text(label) })
            }
        }
        Stepper("Start bit", draft.start, 0, FrameStore.MAX_PAYLOAD * 8 - 1) { draft.start = it }
        Stepper("Size", draft.size, 1, 64) { draft.size = it }
        SwitchRow(draft.littleEndian, if (draft.littleEndian) "Little-endian (start = lowest bit)" else "Big-endian (start = highest bit)") { draft.littleEndian = it }
        SwitchRow(draft.signed, "Signed") { draft.signed = it }
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
        Chart(preview, draft.name, SIGNAL_COLORS[0], view, seconds(model.trace.cursorNow), null, null, onCursor = {}, onMoved = {})
        Histogram(histogram, signal)
        OutlinedTextField(draft.valueNames, { draft.valueNames = it }, Modifier.fillMaxWidth(), label = { Text("Value names") }, placeholder = { Text("0=PARK, 1=DRIVE") })
        SwitchRow(draft.isMultiplexor, "Is the multiplexor: selects which signals a frame carries") { draft.isMultiplexor = it }
        if (!draft.isMultiplexor) OutlinedTextField(draft.muxValue, { draft.muxValue = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Only when the multiplexor is") }, placeholder = { Text("always present") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
}

/** How often each value occurs: a row per value when there are few, bars over the value range when there are many. */
@Composable
private fun Histogram(histogram: List<Pair<String, Int>>, signal: Signal?) {
    if (histogram.isEmpty()) return
    val most = histogram.maxOf { it.second }.coerceAtLeast(1)
    val bar = MaterialTheme.colorScheme.primary
    if (histogram.size <= 16) {
        Hint("${histogram.size} different values")
        for ((label, count) in histogram) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val named = signal?.let { s -> label.toDoubleOrNull()?.let { v -> s.valueNames[Math.round((v - s.offset) / s.factor)] } }
                Text(if (named != null) "$label $named" else label, Modifier.width(96.dp), fontFamily = MONO, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f)) { Box(Modifier.fillMaxWidth(count.toFloat() / most).height(10.dp).clip(CircleShape).background(bar)) }
                Text(count.toString(), Modifier.width(56.dp), fontFamily = MONO, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
            }
        }
    } else {
        Hint("Spread of values, ${histogram.first().first} to ${histogram.last().first} and up")
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for ((_, count) in histogram) Box(Modifier.weight(1f).fillMaxHeight(maxOf(count.toFloat() / most, 0.02f)).background(bar))
        }
    }
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

/** A switch whose label toggles it too: the switch alone is a small target on a phone. */
@Composable
private fun SwitchRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked, null)
        Hint(label, Modifier.padding(start = 10.dp))
    }
}
