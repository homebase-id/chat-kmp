package id.homebase.core.notifications

import id.homebase.core.config.AppConfig
import id.homebase.core.config.COMMUNITY_APP_ID
import id.homebase.core.config.FEED_APP_ID
import id.homebase.core.config.FEED_NEW_COMMENT_TYPE_ID
import id.homebase.core.config.FEED_NEW_CONTENT_TYPE_ID
import id.homebase.core.config.FEED_NEW_REACTION_TYPE_ID
import id.homebase.core.config.MAIL_APP_ID
import id.homebase.core.config.OWNER_APP_ID
import id.homebase.core.config.OWNER_CONNECTION_ACCEPTED_TYPE_ID
import id.homebase.core.config.OWNER_CONNECTION_REQUEST_TYPE_ID
import id.homebase.core.config.OWNER_FOLLOWER_TYPE_ID
import id.homebase.core.config.OWNER_INTRODUCTION_ACCEPTED_TYPE_ID
import id.homebase.core.config.OWNER_INTRODUCTION_RECEIVED_TYPE_ID

/**
 * Formats the notification body text based on the notification payload type. Port of the TypeScript
 * `bodyFormer` function.
 */
object NotificationBodyFormer {

    fun form(
        payload: PushNotification, hasMultiple: Boolean, appName: String, senderName: String?
    ): String {
        val sender = senderName ?: payload.senderId

        // If there's an unencrypted message, use it directly
        payload.options.unEncryptedMessage?.let { message ->
            if (message.isNotBlank()) {
                return message.replace(payload.senderId, sender)
            }
        }

        val appId = payload.options.appId
        val typeId = payload.options.typeId

        return when (appId) {
            OWNER_APP_ID -> when (typeId) {
                OWNER_FOLLOWER_TYPE_ID -> "$sender started following you"
                OWNER_CONNECTION_REQUEST_TYPE_ID -> "$sender sent you a connection request"
                OWNER_CONNECTION_ACCEPTED_TYPE_ID -> "$sender accepted your connection request"

                OWNER_INTRODUCTION_RECEIVED_TYPE_ID -> "$sender introduced you to someone"
                OWNER_INTRODUCTION_ACCEPTED_TYPE_ID -> "$sender confirmed the introduction"
                else -> "$sender sent you a notification via $appName"
            }

            AppConfig.APP_ID -> {
                "$sender sent ${if (hasMultiple) "multiple messages" else "a message"}"
            }

            MAIL_APP_ID -> {
                "$sender sent ${if (hasMultiple) "multiple messages" else "a message"}"
            }

            FEED_APP_ID -> when (typeId) {
                FEED_NEW_CONTENT_TYPE_ID -> "$sender uploaded a new post"
                FEED_NEW_REACTION_TYPE_ID -> "$sender reacted to your post"
                FEED_NEW_COMMENT_TYPE_ID -> "$sender commented on your post"
                else -> "$sender sent you a notification via $appName"
            }

            COMMUNITY_APP_ID -> {
                "$sender sent ${if (hasMultiple) "multiple messages" else "a message"}"
            }

            else -> "$sender sent you a notification via $appName"
        }
    }
}
