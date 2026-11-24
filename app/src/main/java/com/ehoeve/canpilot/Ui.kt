package com.ehoeve.canpilot

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ehoeve.canpilot.core.FrameStore
import com.ehoeve.canpilot.core.Signal

val MONO = FontFamily.Monospace

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
    var finding by remember { mutableStateOf(false) }
    var autoDecoding by remember { mutableStateOf(false) }
    var plotting by remember { mutableStateOf(false) }
    var tracing by remember { mutableStateOf(false) }
    CanPilotTheme {
        when {
            message != null -> {
                BackHandler { selected = null }
                MessageDetail(model, message, onPlots = { selected = null; finding = false; autoDecoding = false; plotting = true }) { selected = null }
            }
            finding -> {
                BackHandler { finding = false }
                FindScreen(model, onBack = { finding = false }) { match -> model.signals.pendingSignal = match.signal; selected = match.message.bus to match.message.id }
            }
            autoDecoding -> {
                BackHandler { autoDecoding = false }
                AutoDecodeScreen(model, onBack = { autoDecoding = false }) { suggestion -> model.signals.pendingSignal = suggestion.signal; selected = suggestion.message.bus to suggestion.message.id }
            }
            plotting -> {
                BackHandler { plotting = false }
                PlotsScreen(model) { plotting = false }
            }
            tracing -> {
                BackHandler { tracing = false }
                TraceScreen(model, onBack = { tracing = false }) { selected = it.bus to it.id }
            }
            else -> MessageList(model, onFrames = { tracing = true }, onFind = { finding = true }, onAuto = { autoDecoding = true }, onPlots = { plotting = true }) { selected = it.bus to it.id }
        }
    }
}

// ---- small pieces

@Composable
fun BusBadge(bus: Int) {
    val color = BUS_COLORS[bus.coerceAtLeast(0) % BUS_COLORS.size]
    Box(Modifier.size(30.dp).clip(MaterialTheme.shapes.extraSmall).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Text(bus.toString(), color = color, fontFamily = MONO, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.surfaceContainerHighest, content: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(shape = CircleShape, color = color, contentColor = content) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

const val LIVE_WINDOW_SECONDS = 30f
