package id.homebase.feed.calls

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import id.homebase.feed.R

/** Keeps the microphone (and camera) alive while a call runs in the background; Android 14 cuts both otherwise. */
class CallForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        val label = intent?.getStringExtra(EXTRA_LABEL)
        val callId = intent?.getStringExtra(IncomingCallNotifier.EXTRA_CALL_ID)
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.call_channel_ongoing), NotificationManager.IMPORTANCE_LOW))
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        val hangup = android.app.PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, CallActionReceiver::class.java).setAction(IncomingCallNotifier.ACTION_HANGUP)
                .putExtra(IncomingCallNotifier.EXTRA_CALL_ID, callId),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val caller = Person.Builder().setName(label ?: getString(R.string.ongoing_call)).setImportant(true).build()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(label ?: getString(R.string.ongoing_call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, hangup))
            .build()
        // phoneCall keeps the service startable when Telecom answers from the background (headset, watch, car).
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        // Android 14 throws SecurityException for a camera-typed service without CAMERA; setLive restarts it once video is allowed.
        if (video && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        ServiceCompat.startForeground(this, ID, notification, types)
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 0x7100_0001
        private const val CHANNEL_ID = "call_ongoing"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_LABEL = "label"

        fun start(context: Context, video: Boolean, callId: String?, label: String?) {
            context.startForegroundService(
                Intent(context, CallForegroundService::class.java)
                    .putExtra(EXTRA_VIDEO, video)
                    .putExtra(IncomingCallNotifier.EXTRA_CALL_ID, callId)
                    .putExtra(EXTRA_LABEL, label),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
