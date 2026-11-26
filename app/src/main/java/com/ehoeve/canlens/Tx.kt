package com.ehoeve.canlens

import com.ehoeve.canlens.core.DashKitStatus
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Build
import android.Manifest
import android.content.Context
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
import com.ehoeve.canlens.core.DashKitLine
import com.ehoeve.canlens.core.FrameStore
import com.ehoeve.canlens.core.TxGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sending frames through a DashKit, on USB or over Bluetooth. The firmware decides when a frame may go on the bus (right after the car's
 * own frame of that id, or not at all); this decides whether to ask: only while armed, never once a CAN controller
 * reports trouble, and changing bits at random only in Park and never in a message that steers, brakes or drives.
 */
class TxState(context: Context, private val store: FrameStore, private val scope: CoroutineScope, private val live: LiveState, private val dbc: DbcState) {
    /** Bluetooth to the DashKit, for when its USB port is the comma 3X's; what it last said about connecting. */
    var bleState by mutableStateOf(""); private set
    private val ble = DashKitBle(context) { bleState = it }
    private var bleClock: Job? = null

    /** The DashKit on USB if that is the live source, else Bluetooth once connected. */
    private val link: TxLink? get() = live.dashKit ?: ble.takeIf { it.connected }
    /** The link that was armed: a different one (USB started, Bluetooth dropped) is not armed until armed itself. */
    private var armedLink: TxLink? = null
    val linked: Boolean get() = link != null
    val status: DashKitStatus? get() = link?.status

    fun connectBle() {
        ble.open()
        if (bleClock == null) bleClock = scope.launch { while (isActive) { delay(1000); ble.everySecond() } }
    }

    fun close() { stop(); bleClock?.cancel(); bleClock = null; ble.close() }

    /** What Bluetooth last read of the DashKit's Wi-Fi (on, address), for the live sheet. */
    val bleStatus: DashKitStatus get() = ble.status

    /**
     * Sends the DashKit its Wi-Fi network and switches its Wi-Fi on, or only switches it off ([ssid] null), over
     * Bluetooth: connecting first if needed. The firmware keeps them.
     */
    fun setUpWifi(ssid: String?, password: String) = scope.launch {
        if (!ble.connected) connectBle()
        for (wait in 0 until 30) { if (ble.connected) break; delay(500) }
        if (!ble.connected) return@launch
        if (ssid == null) {
            ble.send("W,0")
            bleState = "Asked the DashKit to switch its Wi-Fi off"
            // its status is read once a second: confirm from it, for up to 10 s
            for (second in 0 until 10) {
                delay(1000)
                val status = ble.status
                if (status.wifiSeen && !status.wifiOn) {
                    bleState = "The DashKit's Wi-Fi is off" + (if (status.heapKb >= 0) " · ${status.heapKb} KB free" else "") + (if (status.build.isNotEmpty()) " · firmware built ${status.build}" else "")
                    return@launch
                }
            }
            bleState = if (ble.connected) "The DashKit didn't confirm its Wi-Fi is off" else "The Bluetooth link to the DashKit ended"
            return@launch
        }
        ble.send("WS,$ssid"); ble.send("WP,$password"); ble.send("WK,${live.wifiKey()}"); ble.send("W,1")
        bleState = "Sent to the DashKit; waiting for it to join $ssid"
        // the status is read once a second: say what it reports, for a minute or until it is on the network
        for (second in 0 until 60) {
            delay(1000)
            val status = ble.status
            bleState = when {
                !ble.connected -> "The Bluetooth link to the DashKit ended"
                !status.wifiSeen -> "The DashKit's status says nothing of Wi-Fi: it isn't running the wifi-stream firmware. It sent: " +
                    (ble.lastRead?.replace('\n', ' ') ?: "no status yet") + if (ble.lastRefusal != 0) " · a write was refused (${ble.lastRefusal})" else ""
                status.wifiReason != 0 -> "The DashKit can't join $ssid: " + DashKitStatus.wifiProblem(status.wifiReason) + when {
                    status.scanSeen < 0 -> ""
                    status.scanAuth < 0 -> ". Its scan saw ${status.scanSeen} networks, $ssid not among them"
                    else -> ". Its scan saw $ssid on channel ${status.scanChannel} at ${status.scanRssi} dBm (security ${status.scanAuth})"
                }
                !status.wifiOn -> "The DashKit has its Wi-Fi off: it didn't take the settings"
                status.wifiAddress.isNotEmpty() && status.wifiAddress != "0.0.0.0" -> "The DashKit is on $ssid at ${status.wifiAddress}"
                else -> "The DashKit is joining $ssid"
            } + (if (status.heapKb >= 0) " · ${status.heapKb} KB free" else "") + (if (status.build.isNotEmpty()) " · firmware built ${status.build}" else "")
            if (status.wifiAddress.isNotEmpty() && status.wifiAddress != "0.0.0.0") break
        }
    }

    /** What is being sent over time, for the button that stops it; null when nothing is. */
    var running by mutableStateOf<String?>(null); private set
    var note by mutableStateOf(""); private set
    private var job: Job? = null

    val armed: Boolean get() = link.let { it != null && it.armed && it === armedLink }

    /** Arms the current link; disarming disarms every link, USB and Bluetooth, whichever was armed. */
    fun arm(on: Boolean) {
        if (!on) {
            stop()
            for (kit in listOfNotNull(live.dashKit, ble.takeIf { it.connected })) { kit.send(DashKitLine.DISARM); kit.armed = false }
            armedLink = null
            note = ""
            return
        }
        val kit = link ?: return
        kit.send(DashKitLine.ARM)
        kit.armed = true
        armedLink = kit
        note = "Armed. The DashKit watches the bus for two seconds before it sends anything."
    }

    /** Asks for one frame; false when it can't be asked for (not armed, bus trouble, or not a frame the DashKit sends). */
    fun send(bus: Int, id: Int, data: ByteArray): Boolean {
        val kit = link
        if (kit != null && kit !== armedLink && armedLink != null) arm(false)  // the link changed under an armed send: disarm everything
        val line = DashKitLine.transmit(bus, id, data)
        val problem = when {
            kit == null -> "The DashKit is not connected"
            !armed -> "Arm transmitting first"
            kit.status.unhealthy -> "Stopped: a CAN controller of the DashKit reports bus errors, or hasn't reported yet"
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

    /** Sends one frame of [message] if [refusal] allows it now; false (with the reason in [note]) when it doesn't. */
    private fun sendGated(bus: Int, message: FrameStore.Message, data: ByteArray, what: String): Boolean {
        val refusal = refusal(message, bus)
        if (refusal != null) { note = "$what $refusal"; return false }
        return send(bus, message.id, data)
    }

    /** The same frame every [periodMs], while [refusal] allows it. */
    fun repeatFrame(bus: Int, message: FrameStore.Message, data: ByteArray, periodMs: Long) = start("Repeating ${hexId(message.id)}") {
        while (isActive && sendGated(bus, message, data, "Repeating stopped:")) delay(periodMs.coerceAtLeast(10))
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
            if (!isActive || !sendGated(bus, message, frames.data.copyOfRange(i * frames.length, (i + 1) * frames.length), "Replay stopped:")) break
        }
    }

    /** Why [message]'s frames can't be walked, repeated or replayed on [bus] right now; null when they can ([TxGuard.refusal]). */
    fun refusal(message: FrameStore.Message, bus: Int): String? = TxGuard.refusal(
        message.bus, bus, dbc.decoder(message.bus, message.id)?.name, live.source != LiveSource.NONE,
        TxGuard.inPark(store) { dbc.decoder(it.bus, it.id) },
    )

    /** Flips one bit of [base] after the other, each for half a second, and ends on [base]. Stops when the car leaves Park. */
    fun walkBits(bus: Int, message: FrameStore.Message, base: ByteArray) = start("Walking the bits of ${hexId(message.id)}") {
        for (bit in 0 until base.size * 8) {
            val changed = base.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl bit % 8)).toByte() }
            repeat(5) {
                if (!isActive || !sendGated(bus, message, changed, "Stopped at bit $bit:")) return@start
                delay(100)
            }
            note = "Bit $bit of ${base.size * 8} done"
        }
        sendGated(bus, message, base, "The frame as it was not sent back:")
    }
}

/** Sends [message], as it is at the cursor or as typed, through the DashKit. */
@Composable
fun TxSheet(model: AppModel, message: FrameStore.Message, frame: ByteArray?, onClose: () -> Unit) {
    val tx = model.tx
    var bus by remember { mutableIntStateOf(if (message.bus in 0..1) message.bus else 0) }
    var hex by remember { mutableStateOf(hexBytes(frame ?: message.data)) }
    var period by remember { mutableStateOf("100") }
    var confirming by remember { mutableStateOf<String?>(null) }
    val data = remember(hex) { hex.filterNot { it.isWhitespace() }.takeIf { HEX_BYTES.matches(it) }?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray() }
    // the DashKit reports once a second: look again as often
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) tx.connectBle() }
    val tick by produceState(0) { while (true) { delay(500); value++ } }
    val a = model.trace.markA
    val b = model.trace.markB
    val refusal = remember(tick, message, bus) { tx.refusal(message, bus) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Send ${hexId(message.id)}", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(tx.armed && tick >= 0, { if (it) confirming = "arm" else tx.arm(false) })
                Hint("Armed to transmit on the car's bus", Modifier.padding(start = 10.dp))
            }
            if (!tx.linked) {
                Button(onClick = { if (Build.VERSION.SDK_INT >= 31) permission.launch(Manifest.permission.BLUETOOTH_CONNECT) else tx.connectBle() }, modifier = Modifier.fillMaxWidth()) { Text("Connect to the DashKit over Bluetooth") }
                Hint(tx.bleState.ifEmpty { "The DashKit must run the canpilot-link firmware and be paired with this phone through DashPilot. Its USB port can stay on the comma 3X." })
            }
            val status = tx.status
            if (status != null && tick >= 0) {
                Hint("Sent ${status.sent} · refused ${status.refused} · no safe moment ${status.expired}" + status.buses.withIndex().joinToString("") { (index, health) ->
                    if (health == null) "\nBus $index: no report yet" else "\nBus $index errors: transmit ${health.transmitErrors}, receive ${health.receiveErrors}" + if (health.inTrouble) " · IN TROUBLE" else ""
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
                OutlinedButton(enabled = tx.armed && data != null && period.toLongOrNull() != null && refusal == null, onClick = { tx.repeatFrame(bus, message, data!!, period.toLong()) }) { Text("Repeat") }
            }
            if (a != null && b != null) OutlinedButton(enabled = tx.armed && refusal == null, onClick = { tx.replay(bus, message, minOf(a, b), maxOf(a, b)) }, modifier = Modifier.fillMaxWidth()) { Text("Replay its frames between A and B") }
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

/** Whole bytes of hex digits, nothing else ("-1" and "+f" are not bytes). */
private val HEX_BYTES = Regex("([0-9a-fA-F]{2})+")
