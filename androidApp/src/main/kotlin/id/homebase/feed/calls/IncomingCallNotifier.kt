package id.homebase.feed.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import id.homebase.feed.R

/** Heads-up / full-screen "incoming call" notification, replaced or cancelled by callId. */
class IncomingCallNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Incoming calls"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                },
            )
        }
    }

    fun show(callId: String, callerLabel: String) {
        val open = Intent(Intent.ACTION_MAIN).apply {
            setPackage(context.packageName)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_CALL_ID, callId)
        }
        val openPending = PendingIntent.getActivity(
            context, requestCode(callId), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getBroadcast(
            context,
            requestCode(callId) + 1,
            Intent(context, CallActionReceiver::class.java).setAction(ACTION_DECLINE).putExtra(EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val caller = Person.Builder().setName(callerLabel).setImportant(true).build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(callerLabel)
            .setContentText(context.getString(R.string.incoming_call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .setFullScreenIntent(openPending, true)
            .setContentIntent(openPending)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, openPending))
            .build()
        manager.notify(notificationId(callId), notification)
    }

    fun cancel(callId: String) = manager.cancel(notificationId(callId))

    private fun requestCode(callId: String) = notificationId(callId)

    companion object {
        const val CHANNEL_ID = "calls"
        const val EXTRA_CALL_ID = "id.homebase.feed.CALL_ID"
        const val ACTION_DECLINE = "id.homebase.feed.CALL_DECLINE"
        private const val RING_TIMEOUT_MS = 45_000L
        fun notificationId(callId: String) = 0x7000_0000 or (callId.hashCode() and 0x0FFF_FFFF)
    }
}
