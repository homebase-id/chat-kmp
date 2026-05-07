@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package id.homebase.chat.dice

import kotlin.math.sqrt
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * iOS shake detector using `CMMotionManager`'s accelerometer. We threshold on
 * total-acceleration g-force minus 1g so the gesture matches the Android side;
 * we deliberately don't piggy-back on UIKit's `motionEnded:` shake gesture
 * because Compose Multiplatform's `UIViewController` doesn't surface it cleanly,
 * and the accelerometer path also lets us harvest samples for entropy.
 */
class IosShakeDetector : ShakeDetector {

    private val motionManager = CMMotionManager()

    override val isAvailable: Boolean
        get() = motionManager.isAccelerometerAvailable()

    override fun events(): Flow<ShakeEvent> = callbackFlow {
        if (!motionManager.isAccelerometerAvailable()) {
            close()
            return@callbackFlow
        }
        motionManager.accelerometerUpdateInterval = SAMPLE_INTERVAL_S

        var lastEmit = 0.0
        var peak = 0f
        val samples = ArrayDeque<Long>()

        motionManager.startAccelerometerUpdatesToQueue(NSOperationQueue.mainQueue) { data, _ ->
            data ?: return@startAccelerometerUpdatesToQueue
            data.acceleration.useContents {
                val xf = x.toFloat()
                val yf = y.toFloat()
                val zf = z.toFloat()
                val magnitude = sqrt(xf * xf + yf * yf + zf * zf)
                // CoreMotion reports user acceleration in g — subtract 1g of gravity.
                val absG = if (magnitude > 1f) magnitude - 1f else 1f - magnitude

                samples.addLast(magnitude.toRawBits().toLong())
                if (samples.size > MAX_SAMPLES) samples.removeFirst()
                if (absG > peak) peak = absG

                if (absG > SHAKE_THRESHOLD_G) {
                    val now = NSDate().timeIntervalSince1970
                    if ((now - lastEmit) * 1000.0 >= DEBOUNCE_MS) {
                        lastEmit = now
                        trySend(ShakeEvent(intensity = peak, accelSamples = samples.toList()))
                        peak = 0f
                    }
                }
            }
        }
        awaitClose { motionManager.stopAccelerometerUpdates() }
    }

    companion object {
        private const val SHAKE_THRESHOLD_G = 1.6f
        private const val DEBOUNCE_MS = 600.0
        private const val MAX_SAMPLES = 32
        private const val SAMPLE_INTERVAL_S = 1.0 / 60.0
    }
}
