package id.homebase.core.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat

/**
 * Android rich notification displayer using MessagingStyle with circular avatars
 * and conversation grouping (Signal-style).
 */
actual class RichNotificationDisplayer actual constructor() {

    actual fun show(data: RichNotificationData) {
        val context = appContext ?: return
        val icon = if (smallIconResId != 0) smallIconResId else context.applicationInfo.icon

        val person = buildPerson(data.senderName, data.senderId, data.senderImageBytes)

        val contentIntent = buildContentIntent(context, data)

        val builder = NotificationCompat.Builder(context, data.channelId)
            .setSmallIcon(icon)
            .setContentTitle(data.title)
            .setContentText(data.body)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setWhen(if (data.timestamp > 0) data.timestamp else System.currentTimeMillis())
            .setShowWhen(true)
            .setPriority(
                if (data.channelId == "messages") NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )

        // Use MessagingStyle for chat/community conversations
        if (data.conversationId != null) {
            val style = NotificationCompat.MessagingStyle(person)
                .addMessage(data.body, data.timestamp, person)

            if (data.isGroupConversation && data.groupTitle != null) {
                style.isGroupConversation = true
                style.conversationTitle = data.groupTitle
            }

            builder.setStyle(style)
            builder.setGroup(data.conversationId)
            builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(data.body))
        }

        // Set circular avatar as large icon
        data.senderImageBytes?.let { bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                builder.setLargeIcon(circularBitmap(bitmap))
            }
        }

        // Add reply and mark-as-read actions for chat notifications
        if (data.conversationId != null) {
            addNotificationActions(context, builder, data)
        }

        // Set badge count on the notification (used by launcher badge integrations)
        builder.setNumber(BadgeManager.badgeCount)

        // Lock screen: mirror the already content-level-filtered title/body so the lock screen
        // reflects the user's notification content setting (#859). NotificationService sets these
        // per the level — "Homebase"/"New notification" for no_name_or_content, sender + real
        // message for name_content_actions — so no separate redaction is needed here.
        val publicBuilder = NotificationCompat.Builder(context, data.channelId)
            .setSmallIcon(icon)
            .setContentTitle(data.title)
            .setContentText(data.body)
            .setAutoCancel(true)
        builder.setPublicVersion(publicBuilder.build())

        // Suppress sound when chime cooldown is active
        if (data.silent) {
            builder.setSilent(true)
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(data.notificationId, builder.build())

        // Post summary notification for conversation grouping
        if (data.conversationId != null) {
            postSummaryNotification(context, nm, data)
        }
    }

    private fun buildPerson(name: String, key: String, imageBytes: ByteArray?): Person {
        val personBuilder = Person.Builder()
            .setName(name)
            .setKey(key)

        imageBytes?.let { bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                personBuilder.setIcon(IconCompat.createWithBitmap(circularBitmap(bitmap)))
            }
        }

        return personBuilder.build()
    }

    private fun buildContentIntent(context: Context, data: RichNotificationData): PendingIntent {
        // Get the launcher intent to resolve the correct activity component, then clear
        // ACTION_MAIN + CATEGORY_LAUNCHER (which cause Android to just bring the task to
        // foreground without delivering extras when the app is already running).
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        launchIntent.action = null
        launchIntent.categories?.clear()
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        // Pass the notification data so handleNotificationIntent can route
        data.payloadData.forEach { (key, value) -> launchIntent.putExtra(key, value) }
        launchIntent.putExtra(EXTRA_NOTIFICATION_CONVERSATION_ID, data.conversationId)
        launchIntent.putExtra(EXTRA_NOTIFICATION_TAP, true)

        return PendingIntent.getActivity(
            context,
            data.notificationId,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun postSummaryNotification(
        context: Context,
        nm: NotificationManager,
        data: RichNotificationData,
    ) {
        val icon = if (smallIconResId != 0) smallIconResId else context.applicationInfo.icon
        val summaryBuilder = NotificationCompat.Builder(context, data.channelId)
            .setSmallIcon(icon)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText("Homebase"))
            .setGroup(data.conversationId)
            .setGroupSummary(true)
            .setAutoCancel(true)
            // Route a summary tap through the same conversation-tap path as a
            // per-message tap, so tapping the group clears only this conversation.
            .setContentIntent(buildContentIntent(context, data))
        if (data.silent) {
            summaryBuilder.setSilent(true)
        }
        val summary = summaryBuilder.build()

        // Shares conversationNotificationIds()'s summary-id formula so display and
        // cancellation always target the same id.
        val summaryId = data.conversationId?.let { conversationNotificationIds(it).second }
            ?: SUMMARY_ID_OFFSET
        nm.notify(summaryId, summary)
    }

    private fun addNotificationActions(
        context: Context,
        builder: NotificationCompat.Builder,
        data: RichNotificationData,
    ) {
        val conversationId = data.conversationId ?: return

        // Direct Reply action
        val remoteInput = RemoteInput.Builder(EXTRA_REPLY_TEXT)
            .setLabel("Reply")
            .build()

        val replyIntent = Intent(ACTION_REPLY).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_NOTIFICATION_ID, data.notificationId)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            conversationId.hashCode(),
            replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Reply", replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        builder.addAction(replyAction)

        // Mark as Read only when the notification carries real content (#983) —
        // you can't sensibly mark-as-read a "You have a new message" placeholder.
        if (!data.hasContent) return

        val readIntent = Intent(ACTION_MARK_READ).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_NOTIFICATION_ID, data.notificationId)
        }
        val readPendingIntent = PendingIntent.getBroadcast(
            context,
            conversationId.hashCode() + 1,
            readIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val readAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view, "Mark as Read", readPendingIntent
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        builder.addAction(readAction)
    }

    companion object {
        const val ACTION_REPLY = "id.homebase.feed.NOTIFICATION_REPLY"
        const val ACTION_MARK_READ = "id.homebase.feed.NOTIFICATION_MARK_READ"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_REPLY_TEXT = "reply_text"
        const val EXTRA_NOTIFICATION_CONVERSATION_ID = "notification_conversation_id"
        /** Marker extra — always present on intents created by [buildContentIntent]. */
        const val EXTRA_NOTIFICATION_TAP = "notification_tap"

        /** Application context, set during app initialization. */
        internal var appContext: Context? = null

        /** Small icon resource ID for notifications (monochrome silhouette). */
        internal var smallIconResId: Int = 0

        /** Call from Application.onCreate() to provide Context for notification display. */
        fun initialize(context: Context, smallIconResId: Int = 0) {
            appContext = context.applicationContext
            this.smallIconResId = smallIconResId
        }
    }
}

/** Crops a bitmap into a circle. */
private fun circularBitmap(source: Bitmap): Bitmap {
    val size = minOf(source.width, source.height)
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val rect = Rect(0, 0, size, size)
    val rectF = RectF(rect)
    canvas.drawOval(rectF, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(source, rect, rect, paint)
    return output
}
