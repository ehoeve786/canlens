package com.ehoeve.canpilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ehoeve.canpilot.core.DashKitLine
import com.ehoeve.canpilot.core.FrameStore
import com.ehoeve.canpilot.core.TxGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sending frames through a DashKit on USB. The firmware decides when a frame may go on the bus (right after the car's
 * own frame of that id, or not at all); this decides whether to ask: only while armed, never once a CAN controller
 * reports trouble, and changing bits at random only in Park and never in a message that steers, brakes or drives.
 */
class TxState(private val store: FrameStore, private val scope: CoroutineScope, private val live: LiveState, private val dbc: DbcState) {
    /** What is being sent over time, for the button that stops it; null when nothing is. */
    var running by mutableStateOf<String?>(null); private set
    var note by mutableStateOf(""); private set
    private var job: Job? = null

    val armed: Boolean get() = live.dashKit?.armed == true

    fun arm(on: Boolean) {
        val kit = live.dashKit ?: return
        if (!on) { stop(); kit.send(DashKitLine.DISARM) } else kit.send(DashKitLine.ARM)
        kit.armed = on
        note = if (on) "Armed. The DashKit watches the bus for two seconds before it sends anything." else ""
    }

    /** Asks for one frame; false when it can't be asked for (not armed, bus trouble, or not a frame the DashKit sends). */
    fun send(bus: Int, id: Int, data: ByteArray): Boolean {
        val kit = live.dashKit
        val line = DashKitLine.transmit(bus, id, data)
        val problem = when {
            kit == null -> "The DashKit is not connected"
            !kit.armed -> "Arm transmitting first"
            kit.status.unhealthy -> "Stopped: a CAN controller of the DashKit reports bus errors"
            line == null -> "Only standard ids with 1 to 8 bytes on bus 0 or 1 can be sent"
            else -> null
        }
        if (problem != null) { note = problem; return false }
        kit!!.send(line!!)
        return true
    }

    fun stop() { job?.cancel(); job = null; running = null }

    private fun start(what: String, steps: suspend CoroutineScope.() -> Unit) {
        stop()
        running = what
        job = scope.launch {
            steps()
            running = null
        }
    }

    /** The same frame every [periodMs]. */
    fun repeatFrame(bus: Int, id: Int, data: ByteArray, periodMs: Long) = start("Repeating ${hexId(id)}") {
        while (isActive && send(bus, id, data)) delay(periodMs.coerceAtLeast(10))
    }

    /** [message]'s frames between two times again, spaced as they were recorded. */
    fun replay(bus: Int, message: FrameStore.Message, fromTime: Int, toTime: Int) = start("Replaying ${hexId(message.id)}") {
        val frames = withContext(Dispatchers.Default) { store.payloads(message) }
        var last = -1
        for (i in 0 until frames.size) {
            val time = frames.times[i]
            if (time < fromTime || time > toTime) continue
            if (last >= 0) delay((time - last) / 10L)
            last = time
            if (!isActive || !send(bus, message.id, frames.data.copyOfRange(i * frames.length, (i + 1) * frames.length))) break
        }
    }

    /** Why [message]'s bits can't be changed at random right now; null when they can. */
    fun fuzzRefusal(message: FrameStore.Message): String? {
        val name = dbc.decoder(message.bus, message.id)?.name
        return when {
            name != null && TxGuard.critical(name) -> "Not offered for messages that steer, brake, drive, restrain or carry the stalks."
            TxGuard.inPark(store) { dbc.decoder(it.bus, it.id) } != true -> "Only while the car reports Park, from a decoded gear signal."
            else -> null
        }
    }

    /** Flips one bit of [base] after the other, each for half a second, and ends on [base]. Stops when the car leaves Park. */
    fun walkBits(bus: Int, message: FrameStore.Message, base: ByteArray) = start("Walking the bits of ${hexId(message.id)}") {
        for (bit in 0 until base.size * 8) {
            val changed = base.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl bit % 8)).toByte() }
            repeat(5) {
                val refusal = fuzzRefusal(message)
                if (refusal != null) { note = "Stopped at bit $bit. $refusal"; return@start }
                if (!isActive || !send(bus, message.id, changed)) return@start
                delay(100)
            }
            note = "Bit $bit of ${base.size * 8} done"
        }
        send(bus, message.id, base)
    }
}

/** Sends [message], as it is at the cursor or as typed, through the DashKit. */
@Composable
fun TxSheet(model: AppModel, message: FrameStore.Message, frame: ByteArray?, onClose: () -> Unit) {
    val tx = model.tx
    val kit = model.live.dashKit
    var bus by remember { mutableIntStateOf(if (message.bus in 0..1) message.bus else 0) }
    var hex by remember { mutableStateOf(hexBytes(frame ?: message.data)) }
    var period by remember { mutableStateOf("100") }
    var confirming by remember { mutableStateOf<String?>(null) }
    val data = remember(hex) { hex.filterNot { it.isWhitespace() }.takeIf { it.length % 2 == 0 && it.isNotEmpty() }?.chunked(2)?.map { it.toIntOrNull(16)?.toByte() } }
        ?.takeIf { bytes -> bytes.none { it == null } }?.map { it!! }?.toByteArray()
    // the DashKit reports once a second: look again as often
    val tick by produceState(0) { while (true) { delay(500); value++ } }
    val a = model.trace.markA
    val b = model.trace.markB
    val refusal = remember(tick, message) { tx.fuzzRefusal(message) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Send ${hexId(message.id)}", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(tx.armed && tick >= 0, { if (it) confirming = "arm" else tx.arm(false) })
                Hint("Armed to transmit on the car's bus", Modifier.padding(start = 10.dp))
            }
            if (kit != null) {
                val status = kit.status
                Hint("Sent ${status.sent} · refused ${status.refused} · no safe moment ${status.expired}" + (0..1).joinToString("") { index ->
                    if (status.transmitErrors[index] < 0) "" else "\nBus $index errors: transmit ${status.transmitErrors[index]}, receive ${status.receiveErrors[index]}" + if (status.flags[index] and 0x38 != 0) " · IN TROUBLE" else ""
                })
            }
            if (tx.note.isNotEmpty()) Text(tx.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Hint("On bus")
                for (choice in 0..1) FilterChip(selected = bus == choice, onClick = { bus = choice }, label = { Text(choice.toString()) })
            }
            OutlinedTextField(hex, { hex = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Data, hex") }, isError = data == null, textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MONO))
            Hint("Sent as written: a counter or checksum in it is not updated, so a module that checks them ignores the frame. The DashKit sends an id the car also sends only right after the car's own frame, and gives up if no such moment comes.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = tx.armed && data != null, onClick = { tx.send(bus, message.id, data!!) }) { Text("Send once") }
                OutlinedTextField(period, { period = it }, Modifier.width(96.dp), singleLine = true, label = { Text("ms") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedButton(enabled = tx.armed && data != null && period.toLongOrNull() != null, onClick = { tx.repeatFrame(bus, message.id, data!!, period.toLong()) }) { Text("Repeat") }
            }
            if (a != null && b != null) OutlinedButton(enabled = tx.armed, onClick = { tx.replay(bus, message, minOf(a, b), maxOf(a, b)) }, modifier = Modifier.fillMaxWidth()) { Text("Replay its frames between A and B") }
            OutlinedButton(enabled = tx.armed && data != null && refusal == null, onClick = { confirming = "walk" }, modifier = Modifier.fillMaxWidth()) { Text("Flip each bit in turn") }
            if (refusal != null) Hint(refusal)
            tx.running?.let { what -> Button(onClick = { tx.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop: $what") } }
        }
    }
    confirming?.let { what ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(if (what == "arm") "Transmit on the car's bus?" else "Flip the bits of this message?") },
            text = {
                Text(if (what == "arm") "Frames you send reach the car's modules and can make it do things, or raise faults that stay until it sleeps. Send only what you understand, with the car parked."
                else "Each bit is flipped for half a second, on the car, in Park. A module that acts on this message will act on the changed values. It stops when the car leaves Park or the DashKit reports bus errors.")
            },
            confirmButton = { TextButton(onClick = { if (what == "arm") tx.arm(true) else data?.let { tx.walkBits(bus, message, it) }; confirming = null }) { Text(if (what == "arm") "Arm" else "Start") } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}
