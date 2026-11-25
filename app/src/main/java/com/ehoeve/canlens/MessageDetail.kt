package com.ehoeve.canlens

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehoeve.canlens.core.Analysis
import com.ehoeve.canlens.core.BitSummary
import com.ehoeve.canlens.core.FrameStore
import com.ehoeve.canlens.core.Signal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- one message: bits, signals, plots

@Composable
fun MessageDetail(model: AppModel, message: FrameStore.Message, onOpen: (FrameStore.Message) -> Unit, onPlots: () -> Unit) {
    var transmitting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val revision = model.revision
    val store = model.store
    val decoder = model.dbc.decoder(message.bus, message.id)
    val signals = decoder?.signals.orEmpty()
    val drafts = model.signals.drafts
    val draftKey = message.bus to message.id
    val editing = drafts[draftKey]
    // a signal picked in the search opens in the editor, to be checked against its plot and saved
    LaunchedEffect(model.signals.pendingSignal) { model.signals.pendingSignal?.let { drafts[draftKey] = SignalDraft(null, it); model.signals.pendingSignal = null } }
    val cursor = model.trace.cursorNow
    val following = model.trace.followLive && model.live.source != LiveSource.NONE
    val frame = remember(revision, cursor, following) {
        // before the message's first frame: that frame, so its fields can be read anyway
        if (following) message.data else store.frameAt(message, cursor) ?: store.frameAt(message, message.firstTime)
    }
    val a = model.trace.markA
    val b = model.trace.markB
    val activity by produceState<FrameStore.BitActivity?>(null, revision, a, b, message) {
        val window = a != null && b != null
        value = withContext(Dispatchers.Default) {
            store.bitActivity(if (window) minOf(a, b) else Int.MIN_VALUE, if (window) maxOf(a, b) else Int.MAX_VALUE, message).getOrNull(message.index)
        }
    }
    val view = model.plot.view
    if (following) view.show(maxOf(seconds(store.lastTime) - LIVE_WINDOW_SECONDS, seconds(store.firstTime)), seconds(store.lastTime))
    val other = model.other
    val mine = model.mine
    val different = remember(other, mine, message, message.data.size) { if (other == null || mine == null) null else mine.differences(other, message.bus, message.id, message.data.size * 8) }
    val payloads by produceState<FrameStore.Payloads?>(null, revision, message) { value = withContext(Dispatchers.Default) { store.payloads(message) } }
    // each page of a multiplexed message: index in [payloads] of its last frame at the cursor, so its signals show a value whichever page is up
    val pageFrames = remember(payloads, cursor, following, decoder) {
        val mux = decoder?.multiplexor
        val held = payloads
        val pages = signals.mapNotNullTo(HashSet()) { signal -> signal.muxValue.toLong().takeIf { it >= 0 } }
        val found = HashMap<Long, Int>()
        if (mux != null && held != null && mux.fits(held.length) && pages.isNotEmpty()) {
            var k = if (following) held.size - 1 else java.util.Arrays.binarySearch(held.times, 0, held.size, cursor)
            if (k < 0) k = -k - 2 else while (k + 1 < held.size && held.times[k + 1] <= cursor) k++
            while (k >= 0 && found.size < pages.size) {
                val page = mux.raw(held.data, k * held.length)
                if (page in pages) found.putIfAbsent(page, k)
                k--
            }
        }
        found
    }
    val guesses by produceState(emptyList<Signal>(), revision, message, signals) {
        value = withContext(Dispatchers.Default) { Analysis.guessFields(store, message).filter { guess -> signals.none { known -> known.bits.any { it in guess.bits } } } }
    }
    val markerTimes = model.trace.markers.map { seconds(it.time) }
    val plots = model.plot.keys.toList()
    val dbcs = model.dbc.files.toList()
    val liveFrom = if (following) ticks(view.start) else Int.MIN_VALUE
    val series by produceState<Map<PlotKey, FrameStore.Series>>(emptyMap(), revision, plots, dbcs, liveFrom) {
        value = withContext(Dispatchers.Default) {
            plots.mapNotNull { key ->
                val plotMessage = store.messages.firstOrNull { it.bus == key.bus && it.id == key.id } ?: return@mapNotNull null
                val plotDecoder = model.dbc.decoder(key.bus, key.id) ?: return@mapNotNull null
                val signal = plotDecoder.signals.firstOrNull { it.name == key.signal } ?: return@mapNotNull null
                key to store.series(plotMessage, plotDecoder, signal, liveFrom)
            }.toMap()
        }
    }
    val trends = remember(payloads) { payloads?.let { Analysis.byteTrends(it) } ?: "" }
    var muteKnown by rememberSaveable { mutableStateOf(false) }
    // a held bit: the bits of other messages that flip at the same moments
    var similarTo by remember(message) { mutableStateOf<Int?>(null) }
    similarTo?.let { bit ->
        val similar by produceState<List<Analysis.SimilarBit>?>(null, bit) { value = withContext(Dispatchers.Default) { Analysis.similarBits(store, message, bit) } }
        AlertDialog(
            onDismissRequest = { similarTo = null },
            confirmButton = { TextButton(onClick = { similarTo = null }) { Text("Close") } },
            title = { Text("Flips with byte ${bit / 8} bit ${bit % 8}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when {
                        similar == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        similar!!.isEmpty() -> Hint("No other bit flips at the same moments (it has to flip at least twice).")
                        else -> for (hit in similar!!) {
                            val owner = model.dbc.decoder(hit.message.bus, hit.message.id)
                            val name = owner?.signals?.firstOrNull { hit.bit in it.bits }?.name ?: owner?.name ?: ""
                            Row(Modifier.fillMaxWidth().clickable { similarTo = null; onOpen(hit.message) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("bus ${hit.message.bus} · ${hexId(hit.message.id)} · byte ${hit.bit / 8} bit ${hit.bit % 8}", fontFamily = MONO, style = MaterialTheme.typography.bodySmall)
                                    if (name.isNotEmpty()) Hint(name)
                                }
                                Text("${(hit.score * 100).toInt()}%", fontFamily = MONO)
                            }
                        }
                    }
                }
            },
        )
    }
    // the signal being edited is outlined in the bit grid as it is typed
    val drafted = editing?.signal()
    val outlined = when {
        editing == null || drafted == null -> signals
        signals.any { it.name == editing.original } -> signals.map { if (it.name == editing.original) drafted else it }
        else -> signals + drafted
    }
    // the editor gets the whole screen, with the bit grid on top to show which bits the signal takes; tapping a bit moves its start there
    if (editing != null) Dialog(onDismissRequest = { drafts.remove(draftKey) }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                BitGrid(frame, activity, a != null && b != null, different, outlined) { editing.start = it }
                SignalEditor(model, message, decoder?.multiplexor, editing, frame, onClose = { drafts.remove(draftKey) })
            }
        }
    }
    if (transmitting) TxSheet(model, message, frame) { transmitting = false }
    renaming?.let { name ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename ${hexId(message.id)}") },
            text = { OutlinedTextField(name, { renaming = it }, singleLine = true, label = { Text("Message name") }, supportingText = { Text("Letters, digits and _. Saved in the DBC for bus ${message.bus}.") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { model.dbc.renameMessage(message, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                actions = { if (model.dbc.canUndo) TextButton(onClick = { model.dbc.undo() }) { Text("Undo") }; TextButton(onClick = { transmitting = true }) { Text("Send") } },
                title = {
                    Column {
                        // tap the name to rename the message
                        Text(decoder?.name ?: "Message ${hexId(message.id)}", Modifier.clickable { renaming = decoder?.name ?: "UNK_${hexId(message.id)}" },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Hint("bus ${message.bus} · ${hexId(message.id)} · ${message.count} frames · ${"%.1f".format(message.rate)} Hz")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        SectionTitle("Bits", Modifier.padding(horizontal = 14.dp))
                        Hint(
                            (if (a != null && b != null) "Green: changed only between A and B. " else "Blue: changes, brighter is more often. ") +
                                (if (different != null) "Orange: constant here and in ${model.otherName}, but not the same. Brown: changes in only one of them. " else "") +
                                "Tap a bit to define a signal, hold it to find bits elsewhere that flip with it. Beside each byte: ↑ rising, ↓ falling, ⇄ two values, ↕ up and down, ~ nearly every frame.",
                            Modifier.padding(horizontal = 14.dp),
                        )
                        if (signals.isNotEmpty()) FilterChip(muteKnown, { muteKnown = !muteKnown }, { Text("Grey out the DBC's bits") }, Modifier.padding(horizontal = 14.dp))
                        BitGrid(frame, activity, a != null && b != null, different, outlined, trends, muteKnown, onLong = { similarTo = it }) { bit ->
                            val owner = signals.firstOrNull { bit in it.bits }
                            drafts[draftKey] = if (owner != null) SignalDraft(owner.name, owner) else SignalDraft(null, Signal("SIGNAL_${signals.size + 1}", bit, 1, true, false, 1.0, 0.0, "", -1, false))
                        }
                        Hint("History: time runs left to right, one row per bit.", Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp))
                        Box(Modifier.padding(horizontal = 14.dp)) {
                            HistoryStrip(payloads, signals, view, seconds(cursor), onCursor = { model.trace.cursor = ticks(it); model.trace.followLive = false }, onMoved = { model.trace.followLive = false })
                        }
                    }
                }
            }
            if (plots.isNotEmpty()) item {
                Row(Modifier.padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Plots", Modifier.weight(1f))
                    TextButton(onClick = onPlots) { Text("Open plots view") }
                    TextButton(onClick = { model.plot.keys.clear() }) { Text("Clear all") }
                }
            }
            items(plots.size, key = { "plot:${plots[it]}" }) { position ->
                val key = plots[position]
                val own = key.bus == message.bus && key.id == message.id
                // this message's signals keep the colour of their outline in the bit grid
                val color = SIGNAL_COLORS[(if (own) signals.indexOfFirst { it.name == key.signal }.coerceAtLeast(0) else position) % SIGNAL_COLORS.size]
                Chart(series[key], if (own) key.signal else "${key.bus} · ${hexId(key.id)} · ${key.signal}", color, view, seconds(cursor), a?.let { seconds(it) }, b?.let { seconds(it) },
                    onCursor = { model.trace.cursor = ticks(it); model.trace.followLive = false }, onMoved = { model.trace.followLive = false }, markers = markerTimes)
            }
            item {
                Row(Modifier.padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Signals", Modifier.weight(1f))
                    TextButton(onClick = { drafts[draftKey] = SignalDraft(null, Signal("SIGNAL_${signals.size + 1}", 0, 1, true, false, 1.0, 0.0, "", -1, false)) }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("New signal") }
                }
                if (signals.isNotEmpty()) Hint("Tick a signal to plot it. Plots stay up when you open another message.", Modifier.padding(horizontal = 4.dp))
                if (signals.isEmpty()) Hint("No DBC entry for this message. Tap a bit to define a signal.", Modifier.padding(horizontal = 4.dp))
            }
            items(signals.size, key = { "signal:$it" }) { index ->
                val signal = signals[index]
                val active = frame != null && signal.fits(frame.size) &&
                    (signal.muxValue < 0 || decoder?.multiplexor?.let { it.fits(frame.size) && it.raw(frame) == signal.muxValue.toLong() } == true)
                val plotKey = PlotKey(message.bus, message.id, signal.name)
                Surface(onClick = { if (!model.plot.keys.remove(plotKey)) model.plot.keys += plotKey }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(plotKey in plots, onCheckedChange = null)
                        Box(Modifier.padding(start = 10.dp).size(10.dp).clip(CircleShape).background(SIGNAL_COLORS[index % SIGNAL_COLORS.size]))
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(signal.name + STATUS_MARKS[model.dbc.status(message.bus, message.id, signal.name)].orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            // where it sits in the grid: row = byte, bit 7 on the left (the colours repeat after a few signals)
                            Hint(bitsLabel(signal))
                        }
                        // another page's signal: its last value, dimmed
                        val held = payloads
                        Text(
                            when {
                                active -> format(signal, signal.raw(frame))
                                held != null && signal.fits(held.length) -> pageFrames[signal.muxValue.toLong()]?.let { format(signal, signal.raw(held.data, it * held.length)) } ?: "–"
                                else -> "–"
                            },
                            fontFamily = MONO, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            color = if (active) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { drafts[draftKey] = SignalDraft(signal.name, signal) }) { Icon(Icons.Default.Edit, "Edit ${signal.name}", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            if (guesses.isNotEmpty()) item {
                SectionTitle("Suggested fields", Modifier.padding(start = 4.dp, top = 10.dp))
                Hint("Guessed from how the bits flip. Tap one to check it in the editor.", Modifier.padding(horizontal = 4.dp))
            }
            items(guesses.size, key = { "guess:${guesses[it].start}" }) { index ->
                val guess = guesses[index]
                Surface(onClick = { drafts[draftKey] = SignalDraft(null, guess) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(guess.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Hint("bits ${guess.start}–${guess.start + guess.size - 1}", Modifier.padding(end = 10.dp))
                        Icon(Icons.Default.Add, "Define ${guess.name}", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** One row per payload byte, most significant bit on the left. Shade = how often the bit flips; outline = its DBC signal. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BitGrid(frame: ByteArray?, activity: FrameStore.BitActivity?, window: Boolean, different: IntArray?, signals: List<Signal>, trends: String = "", mute: Boolean = false, onLong: (Int) -> Unit = {}, onBit: (Int) -> Unit) {
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
                    val owner = signals.indexOfFirst { index in it.bits }
                    val fill = when {
                        // the DBC already explains it: only what is left unexplained lights up
                        mute && owner >= 0 -> MaterialTheme.colorScheme.surfaceContainerHighest
                        window && inside > 0 && outside == 0 -> Color(0xFF2E7D32)
                        different?.getOrNull(index) == BitSummary.DIFFERENT -> DIFFERENT
                        different?.getOrNull(index) == BitSummary.VARIES_IN_ONE -> VARIES_IN_ONE
                        inside > 0 -> Color(0xFF1976D2).copy(alpha = heat)
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                    Box(
                        Modifier.padding(1.5.dp).size(33.dp).clip(cell).background(fill)
                            .border(2.dp, if (owner >= 0) SIGNAL_COLORS[owner % SIGNAL_COLORS.size] else Color.Transparent, cell)
                            .combinedClickable(onLongClick = { onLong(index) }) { onBit(index) },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (frame[byte].toInt() shr bit and 1 == 1) "1" else "0", fontFamily = MONO, style = MaterialTheme.typography.bodyMedium) }
                }
                Text("%02X".format(frame[byte].toInt() and 0xFF) + " " + trends.getOrElse(byte) { ' ' }, Modifier.padding(start = 10.dp), fontFamily = MONO, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** How a signal's status shows after its name. */
val STATUS_MARKS = mapOf("guess" to "  ?", "plotted" to "  ~", "confirmed" to "  ✓")

/** A signal's place in the bit grid, by its bit number (what the editor's start bit is) and by byte and bit within it. */
fun bitsLabel(signal: Signal): String {
    val low = signal.bits.min()
    val high = signal.bits.max()
    return when {
        low == high -> "bit $low · byte ${low / 8}, bit ${low % 8}"
        low / 8 == high / 8 -> "bits $low–$high · byte ${low / 8}, bits ${low % 8}–${high % 8}"
        else -> "bits $low–$high, bytes ${low / 8}–${high / 8}"
    }
}
