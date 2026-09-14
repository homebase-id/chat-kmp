package id.homebase.core.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual fun getProximityAudioRouter(): ProximityAudioRouter = WebProximityAudioRouter

private object WebProximityAudioRouter : ProximityAudioRouter {
    override val isNearEar: StateFlow<Boolean> = MutableStateFlow(false)
    override fun start() = Unit
    override fun stop() = Unit
}
