package id.homebase.feed.calls

import android.content.ComponentName
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import co.touchlab.kermit.Logger
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallService
import id.homebase.chat.services.calling.isRingingFor
import java.util.concurrent.ConcurrentHashMap
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Self-managed Telecom registration for incoming calls: gives the OS the call (audio focus, Bluetooth,
 * "in a call" state) while the app keeps its own UI and its own notification.
 */
class HomebaseConnectionService : ConnectionService(), KoinComponent {
    private val callService: CallService by inject()

    override fun onCreateIncomingConnection(
        account: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val extras = request?.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
        val callId = extras?.getString(IncomingCallNotifier.EXTRA_CALL_ID).orEmpty()
        val label = extras?.getString(EXTRA_LABEL).orEmpty()
        if (!stillRinging(callId)) {
            Logger.i(tag = TAG) { "telecom incoming call=$callId cancelled: no longer ringing" }
            return Connection.createCanceledConnection()
        }
        val connection = object : Connection() {
            override fun onShowIncomingCallUi() {
                val ringing = stillRinging(callId)
                Logger.i(tag = TAG) { "telecom onShowIncomingCallUi call=$callId stillRinging=$ringing" }
                if (ringing) IncomingCallNotifier(applicationContext).show(callId, labels[callId] ?: label)
            }
            override fun onAnswer() {
                Logger.i(tag = TAG) { "telecom onAnswer call=$callId" }
                answer(callId)
            }
            override fun onReject() {
                Logger.i(tag = TAG) { "telecom onReject call=$callId" }
                if (callService.state.value.callId == callId) callService.decline()
                end(callId, DisconnectCause.REJECTED)
            }
            override fun onDisconnect() {
                Logger.i(tag = TAG) { "telecom onDisconnect call=$callId" }
                if (callService.state.value.callId == callId) callService.hangup()
                end(callId, DisconnectCause.LOCAL)
            }
            override fun onAbort() {
                Logger.i(tag = TAG) { "telecom onAbort call=$callId" }
                end(callId, DisconnectCause.CANCELED)
            }
        }.apply {
            connectionProperties = Connection.PROPERTY_SELF_MANAGED
            setAddress(Uri.fromParts("homebase", label.ifBlank { "caller" }, null), TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName(label, TelecomManager.PRESENTATION_ALLOWED)
            audioModeIsVoip = true
            setRinging()
        }
        connections[callId] = connection
        return connection
    }

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        val extras = request?.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
        val callId = extras?.getString(IncomingCallNotifier.EXTRA_CALL_ID).orEmpty()
        Logger.w(tag = TAG) { "telecom refused incoming call=$callId stillRinging=${stillRinging(callId)}" }
        if (stillRinging(callId)) {
            IncomingCallNotifier(applicationContext).show(callId, labels[callId] ?: extras?.getString(EXTRA_LABEL).orEmpty())
        }
    }

    private fun stillRinging(callId: String) = callService.state.value.isRingingFor(callId)

    // Background activity launches are blocked on Android 10+, so answer in place whenever the mic is already allowed.
    private fun answer(callId: String) {
        if (!stillRinging(callId)) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            applicationContext.startActivity(IncomingCallNotifier.openIntent(applicationContext, callId, accept = true))
            return
        }
        callService.acceptRinging(this)
    }

    companion object {
        const val EXTRA_LABEL = "id.homebase.feed.CALL_LABEL"
        private const val TAG = "AndroidCall"
        private val connections = ConcurrentHashMap<String, Connection>()
        val labels = ConcurrentHashMap<String, String>()

        fun handle(context: Context) = PhoneAccountHandle(ComponentName(context, HomebaseConnectionService::class.java), "homebase-calls")

        fun register(context: Context) {
            val telecom = context.getSystemService(TelecomManager::class.java)
            telecom.registerPhoneAccount(
                PhoneAccount.builder(handle(context), "Homebase")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .build(),
            )
        }

        /** Returns false when Telecom refused, so the caller can post the notification itself. */
        fun reportIncoming(context: Context, callId: String, label: String): Boolean = try {
            val extras = Bundle().apply {
                putString(IncomingCallNotifier.EXTRA_CALL_ID, callId)
                putString(EXTRA_LABEL, label)
            }
            context.getSystemService(TelecomManager::class.java).addNewIncomingCall(
                handle(context),
                Bundle().apply { putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, extras) },
            )
            true
        } catch (e: SecurityException) {
            Logger.w(e, tag = TAG) { "telecom addNewIncomingCall refused call=$callId" }
            false
        }

        fun setActive(callId: String) { connections[callId]?.setActive() }

        // Removing first makes the second of Telecom's callback and the call state's own end a no-op.
        fun end(callId: String, cause: Int) {
            connections.remove(callId)?.let {
                Logger.i(tag = TAG) { "telecom end call=$callId cause=$cause" }
                it.setDisconnected(DisconnectCause(cause))
                it.destroy()
            }
        }
    }
}

internal fun CallEndReason.disconnectCause(): Int = when (this) {
    is CallEndReason.Hangup -> if (remote) DisconnectCause.REMOTE else DisconnectCause.LOCAL
    CallEndReason.Declined -> DisconnectCause.REJECTED
    CallEndReason.Missed, CallEndReason.Cancelled -> DisconnectCause.MISSED
    CallEndReason.Busy -> DisconnectCause.BUSY
    is CallEndReason.Failed -> DisconnectCause.ERROR
    CallEndReason.AnsweredElsewhere, CallEndReason.DeclinedElsewhere -> DisconnectCause.ANSWERED_ELSEWHERE
}
