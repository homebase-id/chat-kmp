package id.homebase.core.notifications

import id.homebase.api.share.NotificationContentLevel

/** Names and the sender's avatar (icon slot only, never the attachment / big-picture slot). */
val NotificationContentLevel.showsNames: Boolean
    get() = this != NotificationContentLevel.NO_NAME_OR_CONTENT

/** The message text and its own photo, the only thing the attachment / big-picture slot carries. */
val NotificationContentLevel.showsContent: Boolean
    get() = this == NotificationContentLevel.NAME_CONTENT_ACTIONS

/**
 * What a chat notification shows, decided once here so every platform's displayer
 * (Android MessagingStyle, the desktop toast, the iOS app) renders the same thing.
 *
 * - 1:1: [title] is the sender, [body] the message.
 * - Group: [title] is the group name, [body] is "Sender: message"; [messageText] keeps the
 *   unprefixed message for displayers that show the sender natively (Android MessagingStyle).
 * - Without [showsContent] the message is [ChatNotificationLabels.redactedBody]; without
 *   [showsNames] the title is [ChatNotificationLabels.appTitle] and no group is shown.
 */
data class ChatNotificationContent(
    val title: String,
    val body: String,
    val messageText: String,
    val groupTitle: String?,
)

class ChatNotificationLabels(
    val appTitle: String,
    val redactedBody: String,
    val senderPrefix: suspend (sender: String, text: String) -> String,
)

/**
 * @param groupTitle null for a 1:1 conversation; a blank name is a group titled
 *   [ChatNotificationLabels.appTitle] so the sender stays visible.
 * @param text the resolved message text, or its media label ("Image") when it has no text.
 */
suspend fun chatNotificationContent(
    level: NotificationContentLevel,
    senderName: String,
    groupTitle: String?,
    text: String,
    labels: ChatNotificationLabels,
): ChatNotificationContent {
    if (!level.showsNames) {
        return ChatNotificationContent(labels.appTitle, labels.redactedBody, labels.redactedBody, groupTitle = null)
    }
    val messageText = if (level.showsContent) text else labels.redactedBody
    val group = groupTitle?.ifBlank { labels.appTitle }
    return ChatNotificationContent(
        title = group ?: senderName,
        body = if (group != null) labels.senderPrefix(senderName, messageText) else messageText,
        messageText = messageText,
        groupTitle = group,
    )
}
