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
import platform.Intents.*
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationAttachment
import platform.UserNotifications.UNNotificationContent
import platform.UserNotifications.UNNotificationContentProvidingProtocol
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS rich notification displayer.
 *
 * Uses UNMutableNotificationContent with:
 * - Communication Notification style via INSendMessageIntent (chat/community)
 * - Sender avatar as notification attachment (non-chat fallback)
 * - Thread identifier for conversation grouping
 * - Category identifier for actions (reply, mark as read)
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

        // For chat/community notifications, apply Communication Notification style.
        // For other notification types, attach avatar as a regular attachment.
        val finalContent: UNNotificationContent = if (data.conversationId != null) {
            applyCommunicationStyle(data, content) ?: run {
                attachAvatarImage(data, content)
                content
            }
        } else {
            attachAvatarImage(data, content)
            content
        }

        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = data.notificationId.toString(),
            content = finalContent,
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

    /**
     * Applies Communication Notification style via INSendMessageIntent.
     * Returns the updated content on success, or null on failure (caller falls back to regular style).
     */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun applyCommunicationStyle(
        data: RichNotificationData,
        content: UNMutableNotificationContent,
    ): UNNotificationContent? {
        return try {
            // Step 1: Create INImage from sender avatar (optional)
            val senderImage = data.senderImageBytes?.takeIf { it.isNotEmpty() }?.let { bytes ->
                val nsData = bytes.usePinned { pinned ->
                    NSData.create(
                        bytes = pinned.addressOf(0),
                        length = bytes.size.toULong()
                    )
                }
                INImage.imageWithImageData(nsData)
            }

            // Step 2: Create INPerson for the sender
            val personHandle = INPersonHandle(
                value = data.senderId,
                type = INPersonHandleTypeUnknown
            )
            val sender = INPerson(
                personHandle = personHandle,
                nameComponents = null,
                displayName = data.senderName,
                image = senderImage,
                contactIdentifier = null,
                customIdentifier = data.senderId
            )

            // Step 3: Create INSendMessageIntent
            val speakableGroupName = if (data.isGroupConversation && data.groupTitle != null) {
                INSpeakableString(spokenPhrase = data.groupTitle)
            } else null

            val intent = INSendMessageIntent(
                recipients = null,
                outgoingMessageType = 0, // INOutgoingMessageTypeUnknown
                content = data.body,
                speakableGroupName = speakableGroupName,
                conversationIdentifier = data.conversationId,
                serviceName = "Homebase",
                sender = sender,
                attachments = null
            )

            // Step 4: Donate INInteraction (fire-and-forget for Siri suggestions)
            val interaction = INInteraction(intent = intent, response = null)
            interaction.donateInteractionWithCompletion { error ->
                if (error != null) {
                    Logger.w(tag = "RichNotificationDisplayer") {
                        "INInteraction donation failed: ${error.localizedDescription}"
                    }
                }
            }

            // Step 5: Update notification content with Communication style
            // INSendMessageIntent conforms to UNNotificationContentProviding,
            // so we use contentByUpdatingWithProvider which is the K/N interop name
            // for [UNNotificationContent contentByUpdating:error:]
            // INSendMessageIntent conforms to UNNotificationContentProviding at the ObjC
            // level (iOS 15+) but K/N doesn't reflect the category-based conformance,
            // so we cast through the protocol interface.
            @Suppress("UNCHECKED_CAST")
            val provider = intent as UNNotificationContentProvidingProtocol
            content.contentByUpdatingWithProvider(provider, error = null)
        } catch (e: Exception) {
            Logger.w(tag = "RichNotificationDisplayer") {
                "Failed to apply Communication style: ${e.message}"
            }
            null
        }
    }

    /**
     * Attaches sender avatar as a UNNotificationAttachment (fallback for non-chat notifications
     * or when Communication style fails).
     */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun attachAvatarImage(data: RichNotificationData, content: UNMutableNotificationContent) {
        data.senderImageBytes?.takeIf { it.isNotEmpty() }?.let { bytes ->
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
    }
}
