package id.homebase.chat.dice

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Android shake detector using the linear-acceleration accelerometer. Treats a
 * spike past [SHAKE_THRESHOLD_G] as a shake, debounced by [DEBOUNCE_MS] so a
 * single shake gesture (which produces several spikes over ~300ms) emits one
 * event. Gravity is removed by subtracting 1g from the magnitude.
 */
class AndroidShakeDetector(context: Context) : ShakeDetector {

    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    override val isAvailable: Boolean = accelerometer != null

    override fun events(): Flow<ShakeEvent> = callbackFlow {
        val sm = sensorManager
        val sensor = accelerometer
        if (sm == null || sensor == null) {
            close()
            return@callbackFlow
        }

        var lastEmitMs = 0L
        var peak = 0f
        val samples = ArrayDeque<Long>()

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x * x + y * y + z * z)
                val gForce = magnitude / SensorManager.GRAVITY_EARTH - 1f
                val absG = if (gForce < 0f) -gForce else gForce

                samples.addLast(magnitude.toRawBits().toLong())
                if (samples.size > MAX_SAMPLES) samples.removeFirst()
                if (absG > peak) peak = absG

                if (absG > SHAKE_THRESHOLD_G) {
                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs >= DEBOUNCE_MS) {
                        lastEmitMs = now
                        trySend(ShakeEvent(intensity = peak, accelSamples = samples.toList()))
                        peak = 0f
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sm.unregisterListener(listener) }
    }

    companion object {
        private const val SHAKE_THRESHOLD_G = 1.6f
        private const val DEBOUNCE_MS = 600L
        private const val MAX_SAMPLES = 32
    }
}
