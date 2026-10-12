package id.homebase.feed.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import id.homebase.chat.services.calling.CallService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class CallActionReceiver : BroadcastReceiver(), KoinComponent {
    private val callService: CallService by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(IncomingCallNotifier.EXTRA_CALL_ID) ?: return
        if (intent.action == IncomingCallNotifier.ACTION_DECLINE) {
            callService.decline()
            IncomingCallNotifier(context).cancel(callId)
        }
    }
}
