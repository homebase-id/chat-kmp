package id.homebase.core.location.tracking

import platform.CoreMotion.CMPedometer
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UIKit.UIDevice
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

actual fun createDeviceSensors(): DeviceSensors = AppleDeviceSensors

private object AppleDeviceSensors : DeviceSensors {

    private val pedometer = CMPedometer()

    init {
        UIDevice.currentDevice.batteryMonitoringEnabled = true
    }

    override suspend fun batteryPercent(): Int? {
        // -1.0 when monitoring is off or unsupported (e.g. simulator).
        val level = UIDevice.currentDevice.batteryLevel
        if (level < 0f) return null
        return (level * 100f).toInt().coerceIn(0, 100)
    }

    override suspend fun stepsSince(prevPointTimeMs: Long?, lastCumulative: Long?): StepSample {
        // iOS answers historical intervals directly — no cumulative needed.
        if (prevPointTimeMs == null) return StepSample(null, null)
        if (!CMPedometer.isStepCountingAvailable()) return StepSample(null, null)
        val from = NSDate.dateWithTimeIntervalSince1970(prevPointTimeMs / 1000.0)
        val to = NSDate()
        val delta = suspendCancellableCoroutine<Int?> { cont ->
            pedometer.queryPedometerDataFromDate(from, to) { data, _ ->
                if (cont.isActive) cont.resume(data?.numberOfSteps?.intValue)
            }
        }
        return StepSample(delta, null)
    }
}
