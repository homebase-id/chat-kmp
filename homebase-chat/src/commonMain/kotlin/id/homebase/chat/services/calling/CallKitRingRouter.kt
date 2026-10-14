package id.homebase.chat.services.calling

import id.homebase.core.notifications.CallKitBridge
import id.homebase.core.notifications.CallPushRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Routes VoIP pushes into the call stack; iOS has already put every one of them on CallKit, so a ring that does not leave the call ringing is ended there. */
class CallKitRingRouter(
    private val target: CallPushTarget,
    private val bridge: () -> CallKitBridge?,
    private val scope: CoroutineScope,
) : CallPushRouter.Handler {
    override fun onRing(callId: String, from: String, sentAtMs: Long) {
        val processed = target.pushRing(callId, from, sentAtMs)
        scope.launch {
            // With no identity loaded the ring is never processed; by the ring timeout the caller has given up anyway.
            val state = try {
                withTimeoutOrNull(RING_TIMEOUT_MS) { processed.await() }
            } catch (e: CancellationException) {
                if (!processed.isCancelled) throw e
                null
            }
            if (state == null || !state.isRingingFor(callId)) bridge()?.reportEnded(callId, remoteEnded = true)
        }
    }

    override fun onHangup(callId: String, from: String) = target.onPushHangup(callId)
}
