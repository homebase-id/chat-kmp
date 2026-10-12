package id.homebase.chat.services.calling

import id.homebase.core.notifications.CallKitBridge
import id.homebase.core.notifications.CallPushRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

interface CallRingPushTarget {
    /** Completes with the call state once the ring has been processed. */
    fun pushRing(callId: String, from: String): Deferred<CallState>
    fun onPushHangup(callId: String)
}

/** Routes VoIP pushes into the call stack; iOS has already put every one of them on CallKit, so a ring that does not leave the call ringing is ended there. */
class CallKitRingRouter(
    private val target: CallRingPushTarget,
    private val bridge: () -> CallKitBridge?,
    private val scope: CoroutineScope,
) : CallPushRouter.Handler {
    override fun onRing(callId: String, from: String, message: String?) {
        val processed = target.pushRing(callId, from)
        scope.launch {
            val state = try {
                processed.await()
            } catch (e: CancellationException) {
                null
            }
            if (state == null || !state.isRingingFor(callId)) bridge()?.reportEnded(callId, remoteEnded = true)
        }
    }

    override fun onHangup(callId: String, from: String) = target.onPushHangup(callId)
}
