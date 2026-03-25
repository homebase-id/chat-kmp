package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationAttachment
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS rich notification displayer.
 *
 * Uses UNMutableNotificationContent with:
 * - Sender avatar as notification attachment
 * - Thread identifier for conversation grouping
 * - Category identifier for actions (reply, mark as read)
 *
 * TODO: Add INSendMessageIntent for communication notification style (iOS 15+)
 * when the Communication Notifications entitlement is configured in Xcode.
 */
actual class RichNotificationDisplayer actual constructor() {

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    actual fun show(data: RichNotificationData) {
        val content = UNMutableNotificationContent().apply {
            setTitle(data.title)
            setBody(data.body)
            setSound(UNNotificationSound.defaultSound)

            // Group by conversation
            if (data.conversationId != null) {
                setThreadIdentifier(data.conversationId)
                setCategoryIdentifier("MESSAGE_CATEGORY")
            }

            // Pass payload for notification tap handling
            val userInfo = data.payloadData.toMap<Any?, Any?>()
            setUserInfo(userInfo)
        }

        // Attach sender avatar image
        data.senderImageBytes?.let { bytes ->
            try {
                val tempDir = NSTemporaryDirectory()
                val fileName = "${NSUUID().UUIDString}.jpg"
                val filePath = "$tempDir$fileName"

                val nsData = bytes.usePinned { pinned ->
                    NSData.create(
                        bytes = pinned.addressOf(0),
                        length = bytes.size.toULong()
                    )
                }
                nsData.writeToFile(filePath, atomically = true)

                val fileUrl = NSURL.fileURLWithPath(filePath)
                val attachment = UNNotificationAttachment.attachmentWithIdentifier(
                    identifier = "sender_avatar",
                    URL = fileUrl,
                    options = null,
                    error = null
                )
                if (attachment != null) {
                    content.setAttachments(listOf(attachment))
                }
            } catch (e: Exception) {
                Logger.w(tag = "RichNotificationDisplayer") {
                    "Failed to attach avatar: ${e.message}"
                }
            }
        }

        // TODO: Communication notification style (iOS 15+)
        // When entitlement is configured:
        // 1. Create INPerson with sender name + INImage from avatar bytes
        // 2. Create INSendMessageIntent with sender, conversationId
        // 3. Donate INInteraction
        // 4. Call content.updating(from: intent) to get communication style

        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = data.notificationId.toString(),
            content = content,
            trigger = null // Deliver immediately
        )

        UNUserNotificationCenter.currentNotificationCenter()
            .addNotificationRequest(request) { error ->
                if (error != null) {
                    Logger.e(tag = "RichNotificationDisplayer") {
                        "Failed to show notification: ${error.localizedDescription}"
                    }
                }
            }
    }
}
