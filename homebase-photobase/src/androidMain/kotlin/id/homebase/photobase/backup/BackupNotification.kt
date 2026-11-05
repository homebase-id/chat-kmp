package id.homebase.photobase.backup

import id.homebase.resources.photobase_stuck_title
import id.homebase.resources.photobase_stuck_body
import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import id.homebase.resources.MR
import id.homebase.resources.photobase_backup_notification
import id.homebase.resources.photobase_backup_notification_channel
import org.jetbrains.compose.resources.getString

internal const val BACKUP_NOTIFICATION_ID = 7201
private const val STUCK_NOTIFICATION_ID = 7202
private const val CHANNEL_ID = "photo_backup"

internal suspend fun postBackupStuckNotification(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, getString(MR.string.photobase_backup_notification_channel), NotificationManager.IMPORTANCE_LOW),
    )
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, STUCK_NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE)
    }
    manager.notify(
        STUCK_NOTIFICATION_ID,
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(getString(MR.string.photobase_stuck_title))
            .setContentText(getString(MR.string.photobase_stuck_body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build(),
    )
}

internal fun cancelBackupStuckNotification(context: Context) {
    context.getSystemService(NotificationManager::class.java).cancel(STUCK_NOTIFICATION_ID)
}

/** [progress] null shows it as indeterminate. */
internal suspend fun backupNotification(context: Context, progress: Float? = null): Notification {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL_ID, getString(MR.string.photobase_backup_notification_channel), NotificationManager.IMPORTANCE_LOW),
    )
    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(context.applicationInfo.icon)
        .setContentTitle(getString(MR.string.photobase_backup_notification))
        .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
        .setOngoing(true)
        .setSilent(true)
        .build()
}
