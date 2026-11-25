package com.ehoeve.canpilot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.ehoeve.canpilot.core.Dbc
import kotlin.math.roundToInt

/**
 * The phone's own motion, heading, GPS, light and air pressure as frames on bus [PHONE_BUS], ten a second at most, so
 * Decode can name the fields of the car that follow them (speed, acceleration, yaw rate, heading) the way it does
 * with decoded signals. [DBC] describes them; it decodes that bus whatever DBC files are loaded.
 */
class PhoneSensors(private val context: Context, private val write: (id: Int, data: ByteArray) -> Unit) : SensorEventListener, LocationListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val locations = context.getSystemService(LocationManager::class.java)
    private val rotation = FloatArray(9)
    private val angles = FloatArray(3)

    fun start() {
        for (type in TYPES) sensors.getDefaultSensor(type)?.let { sensors.registerListener(this, it, PERIOD_MICROS) }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            runCatching { locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100L, 0f, this, Looper.getMainLooper()) }
    }

    fun stop() {
        sensors.unregisterListener(this)
        locations.removeUpdates(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> write(ACCEL, frame(v[0] * 100, v[1] * 100, v[2] * 100))
            Sensor.TYPE_GYROSCOPE -> write(GYRO, frame(v[0] * 1000, v[1] * 1000, v[2] * 1000))
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, v)
                SensorManager.getOrientation(rotation, angles)
                val degrees = (Math.toDegrees(angles[0].toDouble()) + 360) % 360
                write(HEADING, frame(degrees.toFloat() * 10, Math.toDegrees(angles[1].toDouble()).toFloat() * 10, Math.toDegrees(angles[2].toDouble()).toFloat() * 10))
            }
            Sensor.TYPE_LIGHT -> write(LIGHT, frame(minOf(v[0], 32767f)))
            Sensor.TYPE_PRESSURE -> write(PRESSURE, frame((v[0] - 1000) * 10))
        }
    }

    override fun onLocationChanged(location: Location) {
        write(GPS, frame(location.speed * 360, location.bearing * 10, location.altitude.toFloat(), location.accuracy))
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    /** Up to four values as 16-bit signed little-endian numbers, clamped to their range. */
    private fun frame(vararg values: Float) = ByteArray(8).also { data ->
        values.forEachIndexed { i, value ->
            val raw = value.roundToInt().coerceIn(-32768, 32767)
            data[2 * i] = raw.toByte(); data[2 * i + 1] = (raw shr 8).toByte()
        }
    }

    companion object {
        const val PHONE_BUS = 9
        private const val PERIOD_MICROS = 100_000
        private val TYPES = intArrayOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_LIGHT, Sensor.TYPE_PRESSURE)
        private const val ACCEL = 1
        private const val GYRO = 2
        private const val HEADING = 3
        private const val GPS = 4
        private const val LIGHT = 5
        private const val PRESSURE = 6

        val DBC: Dbc = Dbc.parse("""
            BO_ 1 PHONE_accel: 8 PHONE
             SG_ PHONE_accelX : 0|16@1- (0.01,0) [0|0] "m/s2" Vector__XXX
             SG_ PHONE_accelY : 16|16@1- (0.01,0) [0|0] "m/s2" Vector__XXX
             SG_ PHONE_accelZ : 32|16@1- (0.01,0) [0|0] "m/s2" Vector__XXX
            BO_ 2 PHONE_gyro: 8 PHONE
             SG_ PHONE_gyroX : 0|16@1- (0.001,0) [0|0] "rad/s" Vector__XXX
             SG_ PHONE_gyroY : 16|16@1- (0.001,0) [0|0] "rad/s" Vector__XXX
             SG_ PHONE_gyroZ : 32|16@1- (0.001,0) [0|0] "rad/s" Vector__XXX
            BO_ 3 PHONE_orientation: 8 PHONE
             SG_ PHONE_heading : 0|16@1- (0.1,0) [0|0] "deg" Vector__XXX
             SG_ PHONE_pitch : 16|16@1- (0.1,0) [0|0] "deg" Vector__XXX
             SG_ PHONE_roll : 32|16@1- (0.1,0) [0|0] "deg" Vector__XXX
            BO_ 4 PHONE_gps: 8 PHONE
             SG_ PHONE_gpsSpeed : 0|16@1- (0.01,0) [0|0] "km/h" Vector__XXX
             SG_ PHONE_gpsBearing : 16|16@1- (0.1,0) [0|0] "deg" Vector__XXX
             SG_ PHONE_gpsAltitude : 32|16@1- (1,0) [0|0] "m" Vector__XXX
             SG_ PHONE_gpsAccuracy : 48|16@1- (1,0) [0|0] "m" Vector__XXX
            BO_ 5 PHONE_light: 8 PHONE
             SG_ PHONE_light : 0|16@1- (1,0) [0|0] "lx" Vector__XXX
            BO_ 6 PHONE_pressure: 8 PHONE
             SG_ PHONE_pressure : 0|16@1- (0.1,1000) [0|0] "hPa" Vector__XXX
        """.trimIndent(), "phone")
    }
}
