package id.homebase.feed.calls

import android.content.Context
import co.touchlab.kermit.Logger
import id.homebase.api.client.contacts.ContactInfoGateway
import id.homebase.api.common.OdinId
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallService
import id.homebase.core.notifications.CallPushRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Android glue: ring pushes to notification + Telecom, and a foreground service while a call is live. */
class AndroidCallCoordinator(
    private val context: Context,
    private val callService: CallService,
    private val contacts: ContactInfoGateway,
    private val scope: CoroutineScope,
) : CallPushRouter.Handler {
    private val notifier = IncomingCallNotifier(context)
    private var foregroundRunning = false
    private var shownCallId: String? = null

    fun start() {
        if (!callService.available) return
        runCatching { HomebaseConnectionService.register(context) }
        CallPushRouter.handler = this
        scope.launch {
            callService.state.collect { state ->
                val callId = state.callId
                if (shownCallId != null && (state.phase != CallPhase.Incoming || callId != shownCallId)) {
                    notifier.cancel(shownCallId!!)
                    shownCallId = null
                }
                val live = state.phase == CallPhase.Connecting || state.phase == CallPhase.Connected ||
                    state.phase == CallPhase.Reconnecting || state.phase == CallPhase.Outgoing
                if (live && !foregroundRunning) {
                    runCatching { CallForegroundService.start(context, state.media.localVideo) }
                        .onSuccess { foregroundRunning = true }
                        .onFailure { Logger.w(it, "AndroidCall") { "foreground service refused" } }
                }
                if (!live && foregroundRunning) {
                    CallForegroundService.stop(context)
                    foregroundRunning = false
                }
                if (callId != null) {
                    when (state.phase) {
                        CallPhase.Connected -> HomebaseConnectionService.setActive(callId)
                        is CallPhase.Ended -> HomebaseConnectionService.end(callId, remote = true)
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onRing(callId: String, from: String, message: String?) {
        callService.onPushRing(callId, from)
        scope.launch {
            val label = withTimeoutOrNull(1_500) { runCatching { contacts.displayName(OdinId(from)) }.getOrNull() } ?: from
            shownCallId = callId
            if (!HomebaseConnectionService.reportIncoming(context, callId, label)) notifier.show(callId, label)
        }
    }

    override fun onHangup(callId: String, from: String) {
        callService.onPushHangup(callId)
        notifier.cancel(callId)
        HomebaseConnectionService.end(callId, remote = true)
    }
}
