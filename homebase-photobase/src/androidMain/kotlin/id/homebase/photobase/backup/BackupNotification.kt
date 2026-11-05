package id.homebase.photobase.backup

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
private const val CHANNEL_ID = "photo_backup"

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
