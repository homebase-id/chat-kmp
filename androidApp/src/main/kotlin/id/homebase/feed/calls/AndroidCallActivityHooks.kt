package id.homebase.feed.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallService
import id.homebase.chat.services.calling.CallSession
import id.homebase.chat.services.calling.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val STATE_MIC_REQUEST_FOR = "mic_request_for"
private const val RING_WAIT_MS = 10_000L

/** The activity's half of an incoming call: the lock-screen flags, the mic permission and answering from the notification. Build it as a property so the permission launcher registers before STARTED. */
class AndroidCallActivityHooks(
    private val activity: ComponentActivity,
    // Lazy: the Koin graph may not be up when the activity is built (a pending crash report skips initialisation).
    private val callService: Lazy<CallService>,
    // Process scope, so an activity recreation cannot drop the accept.
    scope: CoroutineScope = ProcessLifecycleOwner.get().lifecycleScope,
) {
    private val acceptor by lazy { RingingCallAcceptor(callService.value, scope) { activity.cameraGranted() } }
    private var micRequestFor: String? = null
    private val requestMic = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val callId = micRequestFor
        micRequestFor = null
        if (callId == null) return@registerForActivityResult
        if (granted) acceptor.accept(callId) else if (callService.value.state.value.callId == callId) callService.value.onPermissionDenied()
    }

    fun restoreState(saved: Bundle?) {
        micRequestFor = saved?.getString(STATE_MIC_REQUEST_FOR)
    }

    fun saveState(outState: Bundle) {
        micRequestFor?.let { outState.putString(STATE_MIC_REQUEST_FOR, it) }
    }

    fun onIntent(intent: Intent) {
        showOverLockScreen(intent)
        acceptFromIntent(intent)
    }

    // An incoming-call launch may show over the lock screen, but only while that call is ringing or live.
    private fun showOverLockScreen(intent: Intent) {
        if (!intent.hasExtra(IncomingCallNotifier.EXTRA_CALL_ID)) return
        activity.setShowWhenLocked(true)
        activity.setTurnScreenOn(true)
        activity.lifecycleScope.launch {
            callService.value.state.first { !it.isActive }
            activity.setShowWhenLocked(false)
            activity.setTurnScreenOn(false)
        }
    }

    private fun acceptFromIntent(intent: Intent) {
        if (!intent.getBooleanExtra(IncomingCallNotifier.EXTRA_ACCEPT, false)) return
        intent.removeExtra(IncomingCallNotifier.EXTRA_ACCEPT)
        val callId = intent.getStringExtra(IncomingCallNotifier.EXTRA_CALL_ID) ?: return
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            acceptor.accept(callId)
        } else {
            micRequestFor = callId
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** Answers [callId] once its ring is up; a cold process may still be waiting for the push. */
internal class RingingCallAcceptor(
    private val session: CallSession,
    private val scope: CoroutineScope,
    private val ringWaitMs: Long = RING_WAIT_MS,
    private val cameraGranted: () -> Boolean,
) {
    fun accept(callId: String): Job = scope.launch {
        val ringing = withTimeoutOrNull(ringWaitMs) {
            session.state.first { it.callId == callId && it.phase == CallPhase.Incoming }
        } ?: return@launch
        session.acceptRinging(cameraGranted(), ringing)
    }
}

private fun Context.cameraGranted() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** A video call is taken as voice when the camera is not allowed, so the engine never asks for it. */
internal fun CallSession.acceptRinging(cameraGranted: Boolean, ringing: CallState = state.value) {
    if (ringing.media.localVideo && !cameraGranted) setVideo(false)
    accept()
}

internal fun CallSession.acceptRinging(context: Context, ringing: CallState = state.value) =
    acceptRinging(context.cameraGranted(), ringing)
