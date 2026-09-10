package id.homebase.core.audio

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceProximityStateDidChangeNotification
import platform.darwin.NSObjectProtocol

actual fun getProximityAudioRouter(): ProximityAudioRouter = IOSProximityAudioRouter

private object IOSProximityAudioRouter : ProximityAudioRouter {
    private val logger = Logger.withTag("ProximityAudioRouter")

    private val _isNearEar = MutableStateFlow(false)
    override val isNearEar: StateFlow<Boolean> = _isNearEar.asStateFlow()

    private var observer: NSObjectProtocol? = null

    override fun start() {
        if (observer != null) return

        val device = UIDevice.currentDevice
        device.proximityMonitoringEnabled = true
        // UIKit refuses the flag on hardware without the sensor, so read it back.
        if (!device.proximityMonitoringEnabled) {
            logger.d { "No proximity sensor — raise-to-ear disabled" }
            return
        }

        observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIDeviceProximityStateDidChangeNotification,
            `object` = device,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { onProximityChanged() },
        )
        onProximityChanged()
    }

    override fun stop() {
        val current = observer ?: return
        observer = null
        NSNotificationCenter.defaultCenter.removeObserver(current)
        UIDevice.currentDevice.proximityMonitoringEnabled = false
        AudioSession.endProximityRouting()
        _isNearEar.value = false
    }

    // iOS blanks the screen itself while proximity monitoring is on; only the route is ours.
    private fun onProximityChanged() {
        val near = UIDevice.currentDevice.proximityState
        _isNearEar.value = near
        // PlayAndRecord is the only category with an earpiece route, and entering it can prompt
        // for the microphone — so it waits until the phone is actually at the ear.
        if (near && !AudioSession.beginProximityRouting()) return
        AudioSession.routeOutput(toEarpiece = near)
    }
}
