package id.homebase.feed.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import co.touchlab.kermit.Logger
import id.homebase.api.client.contacts.ContactInfoGateway
import id.homebase.api.common.OdinId
import id.homebase.chat.services.calling.CallRingCoordinator
import id.homebase.chat.services.calling.CallRingSink
import id.homebase.chat.services.calling.CallService
import id.homebase.chat.services.calling.CallState
import id.homebase.core.notifications.CallPushRouter
import kotlinx.coroutines.CoroutineScope

/** Android glue: ring pushes to notification + Telecom, and a foreground service while a call is live. */
class AndroidCallCoordinator(
    private val context: Context,
    private val callService: CallService,
    private val contacts: ContactInfoGateway,
    private val scope: CoroutineScope,
) {
    fun start() {
        if (!callService.available) return
        runCatching { HomebaseConnectionService.register(context) }
        val coordinator = CallRingCoordinator(
            target = callService,
            state = callService.state,
            sink = AndroidCallRingSink(context),
            labelFor = { contacts.displayName(OdinId(it)) },
            scope = scope,
        )
        CallPushRouter.handler = coordinator
        coordinator.start()
    }
}

private class AndroidCallRingSink(private val context: Context) : CallRingSink {
    private val notifier = IncomingCallNotifier(context)
    private var foregroundRunning = false

    override fun ring(callId: String, label: String) {
        val first = HomebaseConnectionService.labels.put(callId, label) == null
        if (first && HomebaseConnectionService.reportIncoming(context, callId, label)) {
            Logger.i(tag = "AndroidCall") { "ring call=$callId via=telecom ${notifier.interruptionState()}" }
            return
        }
        Logger.i(tag = "AndroidCall") { "ring call=$callId via=notification first=$first" }
        notifier.show(callId, label)
    }

    override fun stopRinging(callId: String) = notifier.cancel(callId, reason = "stopRinging")

    override fun connected(callId: String) = HomebaseConnectionService.setActive(callId)

    override fun ended(callId: String) {
        HomebaseConnectionService.labels.remove(callId)
        notifier.cancel(callId, reason = "ended")
        HomebaseConnectionService.end(callId, remote = true)
    }

    override fun setLive(live: Boolean, video: Boolean, callId: String?) {
        if (live) {
            runCatching { CallForegroundService.start(context, video, callId?.let { HomebaseConnectionService.labels[it] }) }
                .onSuccess { foregroundRunning = true }
                .onFailure { Logger.w(it, "AndroidCall") { "foreground service refused" } }
        } else if (foregroundRunning) {
            CallForegroundService.stop(context)
            foregroundRunning = false
        }
    }
}

/** A video call is taken as voice when the camera is not allowed, so the engine never asks for it. */
internal fun CallService.acceptRinging(context: Context, ringing: CallState = state.value) {
    val cameraGranted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    if (ringing.media.localVideo && !cameraGranted) setVideo(false)
    accept()
}
