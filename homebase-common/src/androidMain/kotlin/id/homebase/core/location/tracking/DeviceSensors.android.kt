package id.homebase.core.location.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import co.touchlab.kermit.Logger
import id.homebase.api.ActivityProvider
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

actual fun createDeviceSensors(): DeviceSensors = AndroidDeviceSensors

private object AndroidDeviceSensors : DeviceSensors {
    private val logger = Logger.withTag("AndroidDeviceSensors")
    private val context: Context get() = ActivityProvider.requireApplicationContext()

    override suspend fun batteryPercent(): Int? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return pct.takeIf { it in 0..100 }
    }

    override suspend fun stepsSince(prevPointTimeMs: Long?, lastCumulative: Long?): StepSample {
        val cumulative = readCumulativeSteps() ?: return StepSample(null, lastCumulative)
        // A decrease means the counter reset (reboot) — can't attribute a delta.
        val delta = if (lastCumulative != null && cumulative >= lastCumulative) {
            (cumulative - lastCumulative).toInt()
        } else null
        return StepSample(delta, cumulative)
    }

    /** TYPE_STEP_COUNTER is a one-shot read: register, take the first event, unregister. */
    private suspend fun readCumulativeSteps(): Long? {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return withTimeoutOrNull(STEP_READ_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values.firstOrNull()?.toLong())
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                val ok = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
                if (!ok && cont.isActive) cont.resume(null)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }.also { if (it == null) logger.d { "step counter read timed out / unavailable" } }
    }

    private const val STEP_READ_TIMEOUT_MS = 2_000L
}
