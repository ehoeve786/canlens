package com.ehoeve.canlens.core

/** What a DashKit reports about transmitting and about its two CAN controllers. Written by the reader thread, read by the UI. */
class DashKitStatus {
    @Volatile var armed = false
    /** Frames it put on the bus, frames it refused (not armed, still watching the bus, list full), frames that found no safe moment. */
    @Volatile var sent = 0L
    @Volatile var refused = 0L
    @Volatile var expired = 0L
    /** Per bus: transmit and receive error counters, and the controller's state flags ([BUS_OFF], [ERROR_PASSIVE]). -1 until reported. */
    val transmitErrors = intArrayOf(-1, -1)
    val receiveErrors = intArrayOf(-1, -1)
    val flags = intArrayOf(0, 0)

    /** True when a controller is off the bus or close to it: the moment to stop sending. */
    val unhealthy: Boolean get() = flags.any { it and (BUS_OFF or ERROR_PASSIVE) != 0 }

    /** Takes an "H" or a "D" line of the firmware; other lines are left alone. */
    fun read(line: String) {
        val fields = line.split(',')
        if (fields[0] == "H" && fields.size >= 5) {
            armed = fields[1] == "1"
            sent = fields[2].toLongOrNull() ?: sent
            refused = fields[3].toLongOrNull() ?: refused
            expired = fields[4].toLongOrNull() ?: expired
        } else if (fields[0] == "D" && fields.size == 4 && fields[2].toLongOrNull(16) == BUS_ERRORS && fields[3].length >= 6) {
            val bus = fields[1].toIntOrNull() ?: return
            if (bus !in transmitErrors.indices) return
            transmitErrors[bus] = fields[3].substring(0, 2).toIntOrNull(16) ?: return
            receiveErrors[bus] = fields[3].substring(2, 4).toIntOrNull(16) ?: return
            flags[bus] = fields[3].substring(4, 6).toIntOrNull(16) ?: return
        }
    }

    companion object {
        /** The firmware's diagnostic record with the CAN error state (can_diag.h, CAN_DIAG_ID_BUS_ERR). */
        const val BUS_ERRORS = 0x1DA50006L
        const val ERROR_PASSIVE = 0x18
        const val BUS_OFF = 0x20
    }
}

/** The rules the app keeps before it asks a DashKit to send; the firmware keeps its own for when to put a frame on the bus. */
object TxGuard {
    private val CRITICAL = Regex("steer|epas|eac_|sccm|stalk|brake|ibst|esp_|abs_|torque|accel|pedal|^di_|inverter|rcm_|airbag|restraint|park|gear", RegexOption.IGNORE_CASE)
    private val PARK = Regex("(^|_)P(ARK)?$", RegexOption.IGNORE_CASE)

    /** Messages that steer, brake, drive, restrain or carry the stalks, by their DBC name: never changed at random. */
    fun critical(messageName: String) = CRITICAL.containsMatchIn(messageName)

    /**
     * Whether the car says it is in Park: true or false from a decoded signal with "gear" in its name whose value
     * has a name, null when the open data has none (then nobody knows, and that is not Park).
     */
    fun inPark(store: FrameStore, decoder: (FrameStore.Message) -> DbcMessage?): Boolean? {
        var answer: Boolean? = null
        for (message in store.messages) {
            val data = message.data
            for (signal in decoder(message)?.signals ?: continue) {
                if (!signal.name.contains("gear", true) || !signal.fits(data.size)) continue
                val name = signal.valueNames[signal.raw(data)] ?: continue
                if (PARK.containsMatchIn(name)) return true
                answer = false
            }
        }
        return answer
    }
}
