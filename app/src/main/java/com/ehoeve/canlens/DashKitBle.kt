package com.ehoeve.canlens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.ehoeve.canlens.core.DashKitLine
import com.ehoeve.canlens.core.DashKitStatus
import java.io.Closeable
import java.util.UUID

/** A way to ask a DashKit to transmit: its USB port or Bluetooth. */
interface TxLink {
    val status: DashKitStatus
    /** While true the firmware is kept armed; it disarms itself two seconds after this stops. */
    var armed: Boolean
    /** Queues a line of [DashKitLine]. */
    fun send(line: String)
}

/**
 * The DashKit's transmit service over Bluetooth LE, for when its USB port belongs to the comma 3X. The phone is
 * already bonded with the DashKit through DashPilot; this opens its own GATT client on that bond, writes transmit
 * lines and reads the status back. It subscribes to nothing and writes no filter, so DashPilot's stream is untouched.
 * Needs BLUETOOTH_CONNECT on Android 12 and later; the caller asks for it.
 */
@SuppressLint("MissingPermission")
class DashKitBle(private val context: Context, private val onState: (String) -> Unit) : TxLink, Closeable {
    override val status = DashKitStatus()
    @Volatile override var armed = false
    @Volatile var connected = false; private set
    /** The last status read as it came, and the last write the DashKit refused (GATT status), for troubleshooting. */
    @Volatile var lastRead: String? = null; private set
    @Volatile var lastRefusal = 0; private set

    private var gatt: BluetoothGatt? = null
    private var transmit: BluetoothGattCharacteristic? = null
    private var control: BluetoothGattCharacteristic? = null
    /** GATT does one thing at a time: what waits its turn, and whether something is under way. */
    private val waiting = ArrayDeque<() -> Boolean>()
    private var busy = false
    private var seconds = 0
    private var refreshed = false

    /** Starts connecting to the bonded DashKit; the result arrives through [onState]. */
    fun open() {
        close()
        refreshed = false
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = runCatching { adapter?.bondedDevices?.firstOrNull { it.name == NAME } }.getOrNull()
        if (adapter == null || !adapter.isEnabled || device == null) {
            onState(if (adapter?.isEnabled != true) "Bluetooth is off" else "No paired DashKit. Pair it in DashPilot first.")
            return
        }
        onState("Connecting to the DashKit")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, state: Int, newState: Int) {
            // a bigger MTU first: the status read (transmit counts, bus errors, Wi-Fi) is longer than the default 20 bytes
            if (newState == BluetoothProfile.STATE_CONNECTED) { if (!g.requestMtu(185)) g.discoverServices() }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) { lost(); onState("The DashKit's Bluetooth link ended") }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, state: Int) { g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, state: Int) {
            transmit = g.getService(TX_SERVICE)?.getCharacteristic(TX_LINES)
            control = g.getService(CAN_SERVICE)?.getCharacteristic(CONTROL)
            // Android remembers a paired device's services: after a firmware update, have it look again, once
            if (transmit == null && !refreshed) { refreshed = true; runCatching { g.javaClass.getMethod("refresh").invoke(g) }; g.discoverServices(); return }
            if (transmit == null) { onState("This DashKit's firmware has no transmit service (it needs the canpilot-link build)"); close(); return }
            connected = true
            onState("Connected over Bluetooth")
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33, and the only one called before it")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, state: Int) {
            val text = if (state == BluetoothGatt.GATT_SUCCESS) characteristic.value?.toString(Charsets.US_ASCII) else null
            lastRead = text ?: "read failed ($state)"
            text?.lines()?.forEach { status.read(it) }
            done()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, state: Int) {
            if (state != BluetoothGatt.GATT_SUCCESS) lastRefusal = state
            done()
        }
    }

    private fun lost() {
        connected = false; armed = false
        synchronized(waiting) { waiting.clear(); busy = false }
    }

    private fun done() {
        synchronized(waiting) { busy = false }
        next()
    }

    private fun next() {
        while (true) {
            val step = synchronized(waiting) { if (busy) null else waiting.removeFirstOrNull()?.also { busy = true } } ?: return
            if (step()) return   // its callback calls done()
            synchronized(waiting) { busy = false }
        }
    }

    private fun queue(step: () -> Boolean) {
        synchronized(waiting) { if (waiting.size >= MAX_WAITING) return; waiting.addLast(step) }
        next()
    }

    @Suppress("DEPRECATION")
    private fun write(characteristic: BluetoothGattCharacteristic?, bytes: ByteArray, withResponse: Boolean) = queue {
        val g = gatt
        if (g == null || characteristic == null) false
        else {
            characteristic.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            characteristic.value = bytes
            g.writeCharacteristic(characteristic)
        }
    }

    /** Frames go without waiting for an answer each; arming and disarming are confirmed. */
    override fun send(line: String) {
        if (connected) write(transmit, line.trimEnd().toByteArray(Charsets.US_ASCII), !line.startsWith("T,"))
    }

    /** Once a second while connected: keeps the firmware armed, reads its status, and now and then says the app is alive. */
    fun everySecond() {
        if (!connected) return
        if (armed) send(DashKitLine.ARM)
        queue { val g = gatt; val c = transmit; g != null && c != null && g.readCharacteristic(c) }
        if (seconds++ % PING_EVERY == 0) write(control, byteArrayOf(PING, 0), true)
    }

    override fun close() {
        lost()
        runCatching { gatt?.close() }
        gatt = null; transmit = null; control = null
    }

    private companion object {
        const val NAME = "DashKit"
        val CAN_SERVICE: UUID = UUID.fromString("CADA0000-CA00-B1E0-B0D6-C000AA0100A1")
        val CONTROL: UUID = UUID.fromString("CADA0004-CA00-B1E0-B0D6-C000AA0100A1")
        val TX_SERVICE: UUID = UUID.fromString("CADA0100-CA00-B1E0-B0D6-C000AA0100A1")
        val TX_LINES: UUID = UUID.fromString("CADA0101-CA00-B1E0-B0D6-C000AA0100A1")
        /** The firmware's keepalive opcode (VC_CMD_PING): it drops a link whose pings stopped. */
        const val PING: Byte = 0x45
        const val PING_EVERY = 15
        /** Lines waiting for the radio; more than this and the newest are dropped: Bluetooth carries a few dozen frames a second. */
        const val MAX_WAITING = 64
    }
}
