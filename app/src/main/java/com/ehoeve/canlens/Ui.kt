package com.ehoeve.canlens

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.Share
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.ehoeve.canlens.core.FrameStore
import com.ehoeve.canlens.core.Signal
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

val MONO = FontFamily.Monospace

fun seconds(time: Int) = time / FrameStore.TICKS_PER_SECOND.toFloat()
/** To the nearest tick: a time that went through [seconds] comes back as the same tick. */
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

/** The time of day of a store [time] ("10:59:22"), or null when the open data has no clock for it. */
fun FrameStore.clock(time: Int): String? = wallMicros(time)?.let { Instant.ofEpochMilli(it / 1000).atZone(ZoneId.systemDefault()).format(CLOCK) }

fun ticks(seconds: Float): Int = (seconds * FrameStore.TICKS_PER_SECOND).let { if (it.isNaN()) 0 else it.roundToInt() }
fun hexId(id: Int) = if (id in 0..0x7FF) "%03X".format(id) else "%08X".format(com.ehoeve.canlens.core.CanId.raw(id))
fun hexBytes(data: ByteArray) = buildString(data.size * 3) { for (b in data) { append("%02X".format(b.toInt() and 0xFF)).append(' ') } }.trimEnd()

fun format(signal: Signal, raw: Long): String {
    signal.valueNames[raw]?.let { return "$it ($raw)" }
    val value = signal.physical(raw)
    val number = if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString() else "%.4f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')
    return if (signal.unit.isEmpty()) number else "$number ${signal.unit}"
}

/** The views that share the cursor and the selected message; the tab bar switches between them. */
enum class Tab(val label: String) { MESSAGES("Messages"), FRAMES("Frames"), MESSAGE("Message"), PLOTS("Plots") }

@Composable
fun App(model: AppModel = viewModel()) {
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    model.revision   // re-resolve the selection when the data changes
    val message = selected?.let { key -> model.store.messages.firstOrNull { it.bus == key.first && it.id == key.second } }
    var tab by remember { mutableStateOf(Tab.MESSAGES) }
    var finding by remember { mutableStateOf(false) }
    var autoDecoding by remember { mutableStateOf(false) }
    var commanding by remember { mutableStateOf(false) }
    val open = { opened: FrameStore.Message -> selected = opened.bus to opened.id; tab = Tab.MESSAGE }
    CanLensTheme {
        when {
            // a search result is checked in the Message tab; Back from there returns to the results
            finding && tab != Tab.MESSAGE -> {
                BackHandler { finding = false }
                FindScreen(model, onBack = { finding = false }) { match -> model.signals.pendingSignal = match.signal; open(match.message) }
            }
            autoDecoding && tab != Tab.MESSAGE -> {
                BackHandler { autoDecoding = false }
                AutoDecodeScreen(model, onBack = { autoDecoding = false }, onPlots = { autoDecoding = false; tab = Tab.PLOTS }) { suggestion -> model.signals.pendingSignal = suggestion.signal; open(suggestion.message) }
            }
            else -> Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),   // each view's own top bar deals with the status bar
                bottomBar = {
                    Column {
                        TimeBar(model)
                        TabBar(tab, onCommand = { commanding = true }) { tab = it; finding = false; autoDecoding = false }
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                    BackHandler(tab != Tab.MESSAGES) { tab = Tab.MESSAGES }
                    when (tab) {
                        Tab.MESSAGES -> MessageList(model, onFind = { finding = true }, onAuto = { autoDecoding = true }, onOpen = open)
                        Tab.FRAMES -> TraceScreen(model, message, onSelect = { selected = it.bus to it.id }, onOpen = open)
                        // keyed by the message: what MessageDetail remembers (the frame at the cursor, the bit activity) is of one message
                        Tab.MESSAGE -> if (message != null) key(message.bus, message.id) { MessageDetail(model, message, onOpen = open) { tab = Tab.PLOTS } }
                            else Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Hint("Pick a message in Messages or Frames to see its bits and signals here.") }
                        Tab.PLOTS -> PlotsScreen(model)
                    }
                }
            }
        }
        if (commanding) CommandSheet(model, onClose = { commanding = false }, onOpen = open, onTab = { tab = it }, onFind = { tab = Tab.MESSAGES; finding = true }, onAuto = { tab = Tab.MESSAGES; autoDecoding = true })
    }
}

@Composable
private fun TabBar(current: Tab, onCommand: () -> Unit, onTab: (Tab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.navigationBarsPadding().fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            for (tab in Tab.entries) {
                val on = tab == current
                Box(Modifier.weight(1f).fillMaxHeight().clickable { onTab(tab) }, contentAlignment = Alignment.Center) {
                    Text(tab.label, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onCommand) { Icon(Icons.Default.Search, "Go to a message, signal, time or filter") }
        }
    }
}

// ---- small pieces

class SaveEntry(val label: String, val fileName: String, val save: (Uri) -> Unit)

/** A button with a menu of things to save; choosing one asks where the file goes and hands that place to the entry. */
@Composable
fun SaveMenu(entries: List<SaveEntry>) {
    var open by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<SaveEntry?>(null) }
    val place = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) chosen?.save?.invoke(uri) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.Share, "Save to a file") }
        DropdownMenu(open, { open = false }) {
            for (entry in entries) DropdownMenuItem(text = { Text(entry.label) }, onClick = { open = false; chosen = entry; place.launch(entry.fileName) })
        }
    }
}

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
