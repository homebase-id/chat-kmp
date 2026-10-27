package id.homebase.core.notifications

import id.homebase.api.share.NotificationContentLevel

/**
 * What a chat notification shows, decided once here so every platform's displayer
 * (Android MessagingStyle, the desktop toast, the iOS app) renders the same thing.
 *
 * - 1:1: [title] is the sender, [body] the message.
 * - Group: [title] is the group name, [body] is "Sender: message"; [messageText] keeps the
 *   unprefixed message for displayers that show the sender natively (Android MessagingStyle).
 * - [showsAvatar]: the sender's avatar may be shown as the notification's icon. It never goes
 *   in the attachment / big-picture slot; that slot is for the message's own photo, and only
 *   when [showsImage].
 * - [NotificationContentLevel.NAME_ONLY] keeps names and the avatar but replaces the message
 *   with [ChatNotificationLabels.redactedBody] and drops the photo.
 *   [NotificationContentLevel.NO_NAME_OR_CONTENT] shows neither names, avatar, group nor photo.
 */
data class ChatNotificationContent(
    val title: String,
    val body: String,
    val messageText: String,
    val groupTitle: String?,
    val showsAvatar: Boolean,
    val showsImage: Boolean,
)

class ChatNotificationLabels(
    val appTitle: String,
    val redactedBody: String,
    val senderPrefix: suspend (sender: String, text: String) -> String,
)

/**
 * @param groupTitle the group's name, or null for a 1:1 conversation. A group without a name
 *   is treated as a group titled [ChatNotificationLabels.appTitle] so the sender stays visible.
 * @param text the resolved message text, or its media label ("Image") when it has no text.
 */
suspend fun chatNotificationContent(
    level: NotificationContentLevel,
    senderName: String,
    groupTitle: String?,
    isGroup: Boolean,
    text: String,
    hasImage: Boolean,
    labels: ChatNotificationLabels,
): ChatNotificationContent {
    if (level == NotificationContentLevel.NO_NAME_OR_CONTENT) {
        return ChatNotificationContent(
            title = labels.appTitle,
            body = labels.redactedBody,
            messageText = labels.redactedBody,
            groupTitle = null,
            showsAvatar = false,
            showsImage = false,
        )
    }
    val showsContent = level == NotificationContentLevel.NAME_CONTENT_ACTIONS
    val messageText = if (showsContent) text else labels.redactedBody
    val group = if (isGroup) groupTitle?.ifBlank { null } ?: labels.appTitle else null
    return ChatNotificationContent(
        title = group ?: senderName,
        body = if (group != null) labels.senderPrefix(senderName, messageText) else messageText,
        messageText = messageText,
        groupTitle = group,
        showsAvatar = true,
        showsImage = showsContent && hasImage,
    )
}
