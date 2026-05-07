package id.homebase.chat.dice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Desktop has no accelerometer, so [isAvailable] is `false` and the composer
 * hides the shake hint. The button still rolls fine.
 */
class JvmShakeDetector : ShakeDetector {
    override val isAvailable: Boolean = false
    override fun events(): Flow<ShakeEvent> = emptyFlow()
}
