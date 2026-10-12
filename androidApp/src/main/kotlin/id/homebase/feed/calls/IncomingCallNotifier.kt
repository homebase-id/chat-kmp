package id.homebase.feed.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import id.homebase.feed.MainActivity
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
        val openPending = PendingIntent.getActivity(
            context, requestCode(callId), openIntent(context, callId, accept = false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val acceptPending = PendingIntent.getActivity(
            context, requestCode(callId) + 2, openIntent(context, callId, accept = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getBroadcast(
            context,
            requestCode(callId) + 1,
            Intent(context, CallActionReceiver::class.java).setAction(ACTION_DECLINE).putExtra(EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val caller = Person.Builder().setName(callerLabel).setImportant(true).build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(callerLabel)
            .setContentText(context.getString(R.string.incoming_call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .setFullScreenIntent(openPending, true)
            .setContentIntent(openPending)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, acceptPending))
        if (!canUseFullScreenIntent()) {
            val settings = PendingIntent.getActivity(
                context, requestCode(callId) + 3, fullScreenSettingsIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, context.getString(R.string.allow_full_screen_calls), settings)
        }
        manager.notify(notificationId(callId), builder.build())
    }

    private fun canUseFullScreenIntent() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent()

    fun cancel(callId: String) = manager.cancel(notificationId(callId))

    private fun requestCode(callId: String) = notificationId(callId)

    companion object {
        const val CHANNEL_ID = "calls"
        const val EXTRA_CALL_ID = "id.homebase.feed.CALL_ID"
        const val EXTRA_ACCEPT = "id.homebase.feed.CALL_ACCEPT"
        const val ACTION_DECLINE = "id.homebase.feed.CALL_DECLINE"
        const val ACTION_HANGUP = "id.homebase.feed.CALL_HANGUP"
        private const val RING_TIMEOUT_MS = 45_000L

        // Explicit: the launcher filter has no CATEGORY_DEFAULT, so an implicit MAIN/LAUNCHER start resolves to nothing.
        fun openIntent(context: Context, callId: String, accept: Boolean): Intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_CALL_ID, callId)
            if (accept) putExtra(EXTRA_ACCEPT, true)
        }

        fun fullScreenSettingsIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", context.packageName, null))
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun notificationId(callId: String) = 0x7000_0000 or (callId.hashCode() and 0x0FFF_FFFF)
    }
}
