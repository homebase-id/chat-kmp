package id.homebase.feed.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class CallActionReceiver : BroadcastReceiver(), KoinComponent {
    private val callService: CallService by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val state = callService.state.value
        when (intent.action) {
            IncomingCallNotifier.ACTION_DECLINE -> {
                val callId = intent.getStringExtra(IncomingCallNotifier.EXTRA_CALL_ID) ?: return
                IncomingCallNotifier(context).cancel(callId, reason = "declineAction")
                if (state.callId == callId && state.phase == CallPhase.Incoming) callService.decline()
            }
            IncomingCallNotifier.ACTION_HANGUP -> {
                val callId = intent.getStringExtra(IncomingCallNotifier.EXTRA_CALL_ID) ?: return
                if (state.callId == callId && state.isActive) callService.hangup()
            }
        }
    }
}
