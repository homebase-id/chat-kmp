package id.homebase.chat.dice

import kotlinx.coroutines.flow.Flow

/**
 * Cross-platform shake detector for the dice composer. Available on Android (via
 * `SensorManager`) and iOS (via `CMMotionManager` + the system shake gesture).
 * On JVM/desktop and web, [isAvailable] is `false` and [events] returns an empty
 * flow so the composer simply hides the shake hint.
 *
 * Platforms register the singleton in their `platformModule()` entry. The
 * detector itself is stateless — `events()` cold-starts the sensor on collect
 * and unregisters when the flow is cancelled, so subscribing only while the
 * composer is open is safe and cheap.
 */
interface ShakeDetector {
    val isAvailable: Boolean
    fun events(): Flow<ShakeEvent>
}

/**
 * One shake observation. [intensity] is the peak acceleration magnitude (m/s²
 * minus 1g) seen during the shake — useful as a "how hard did the user shake"
 * signal. [accelSamples] are the raw magnitude readings (in nanometres/s²·1e-9
 * scaled to Long for cheap XOR-mixing) we captured during the shake; the dice
 * composer XORs these into the RNG seed when the user shakes to roll.
 */
data class ShakeEvent(
    val intensity: Float,
    val accelSamples: List<Long>,
)
