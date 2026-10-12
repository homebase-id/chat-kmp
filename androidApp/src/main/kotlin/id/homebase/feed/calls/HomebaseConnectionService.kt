package id.homebase.feed.calls

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import id.homebase.chat.services.calling.CallService
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
        val connection = object : Connection() {
            override fun onShowIncomingCallUi() = IncomingCallNotifier(applicationContext).show(callId, labels[callId] ?: label)
            override fun onAnswer() {
                applicationContext.startActivity(IncomingCallNotifier.openIntent(applicationContext, callId, accept = true))
            }
            override fun onReject() {
                if (callService.state.value.callId == callId) callService.decline()
                end(DisconnectCause.REJECTED)
            }
            override fun onDisconnect() {
                if (callService.state.value.callId == callId) callService.hangup()
                end(DisconnectCause.LOCAL)
            }
            override fun onAbort() = end(DisconnectCause.CANCELED)
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
        IncomingCallNotifier(applicationContext).show(callId, labels[callId] ?: extras?.getString(EXTRA_LABEL).orEmpty())
    }

    companion object {
        const val EXTRA_LABEL = "id.homebase.feed.CALL_LABEL"
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
        } catch (_: SecurityException) {
            false
        }

        fun setActive(callId: String) { connections[callId]?.setActive() }

        fun end(callId: String, remote: Boolean) {
            connections.remove(callId)?.let {
                it.setDisconnected(DisconnectCause(if (remote) DisconnectCause.REMOTE else DisconnectCause.LOCAL))
                it.destroy()
            }
        }
    }
}

private fun Connection.end(cause: Int) {
    setDisconnected(DisconnectCause(cause))
    destroy()
}
