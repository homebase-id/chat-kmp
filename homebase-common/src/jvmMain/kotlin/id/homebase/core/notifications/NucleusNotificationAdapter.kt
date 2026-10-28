package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import id.homebase.api.browser.DesktopAppFocusManager
import id.homebase.api.file.JvmFileSystemUtil
import id.homebase.core.util.hostOs
import io.github.kdroidfilter.nucleus.notification.common.NotificationHandle
import io.github.kdroidfilter.nucleus.notification.common.NotificationManager
import io.github.kdroidfilter.nucleus.notification.common.NotificationResult
import io.github.kdroidfilter.nucleus.notification.common.notification
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM-only adapter that wraps the Nucleus cross-platform notification API
 * (Windows toast / macOS user notification / Linux libnotify).
 *
 * Avatar and photo bytes are re-encoded to PNG files under the app data directory;
 * Nucleus requires a filesystem path, and the OS notification services don't all read WebP.
 * Which image goes in which slot is [notificationImageSlots].
 */
internal class NucleusNotificationAdapter private constructor() {

    private val avatarCache = ConcurrentHashMap<String, String>()
    private val posted = ConcurrentHashMap<Int, NotificationHandle>()
    private val cacheDir: File = File(JvmFileSystemUtil.getAppDataDirectory(), "notification-cache").apply {
        if (!exists()) mkdirs()
    }

    fun show(data: RichNotificationData) {
        try {
            Logger.i(tag = "NucleusNotificationAdapter") {
                "posting notification ${data.notificationId}"
            }
            val slots = notificationImageSlots(
                os = hostOs,
                hasAvatar = data.senderImageBytes != null,
                hasImage = data.imageBytes != null,
            )
            val largeImage = when (slots.largeImage) {
                ImageSource.Photo -> data.imageBytes?.let(::photoPath)
                ImageSource.Avatar -> data.senderImageBytes?.let { cachedAvatarPath(it, circle = false) }
                null -> null
            }
            val smallIcon = data.senderImageBytes?.takeIf { slots.smallIconIsAvatar }
                ?.let { cachedAvatarPath(it, circle = true) }
            val result = notification(
                title = data.title,
                message = data.body,
                largeImage = largeImage,
                smallIcon = smallIcon,
                onActivated = {
                    Logger.i(tag = "NucleusNotificationAdapter") {
                        "onActivated fired for ${data.notificationId}"
                    }
                    DesktopAppFocusManager.requestFocus("notification-activated")
                    NotificationClickRouter.onClick(data.payloadData)
                },
                onFailed = {
                    Logger.w(tag = "NucleusNotificationAdapter") {
                        "Nucleus reported delivery failure for notification ${data.notificationId}"
                    }
                },
            ).send()
            if (result is NotificationResult.Success) posted[data.notificationId] = result.handle
            if (result is NotificationResult.Failure) {
                Logger.w(tag = "NucleusNotificationAdapter") {
                    "Send failed: ${result.reason}"
                }
            }
        } catch (e: Exception) {
            Logger.e(tag = "NucleusNotificationAdapter") {
                "Failed to show notification: ${e.message}"
            }
        }
    }

    fun showCall(notificationId: Int, title: String, body: String, actions: CallNotificationActions) {
        try {
            posted.remove(notificationId)?.dismiss()
            val result = notification(
                title = title,
                message = body,
                onActivated = actions.onOpen,
                onFailed = {
                    Logger.w(tag = "NucleusNotificationAdapter") { "Nucleus reported delivery failure for call notification" }
                },
            ) {
                button(actions.acceptLabel, actions.onAccept)
                button(actions.declineLabel, actions.onDecline)
            }.send()
            if (result is NotificationResult.Success) posted[notificationId] = result.handle
            else if (result is NotificationResult.Failure) {
                Logger.w(tag = "NucleusNotificationAdapter") { "Call notification send failed: ${result.reason}" }
            }
        } catch (e: Exception) {
            Logger.e(tag = "NucleusNotificationAdapter") { "Failed to show call notification: ${e.message}" }
        }
    }

    fun dismiss(notificationId: Int) {
        runCatching { posted.remove(notificationId)?.dismiss() }
    }

    private fun cachedAvatarPath(bytes: ByteArray, circle: Boolean): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
            val key = digest.joinToString("") { "%02x".format(it) } + if (circle) "-circle" else ""
            avatarCache[key]?.let { existing ->
                if (File(existing).exists()) return existing
            }
            val file = File(cacheDir, "$key.png")
            if (!file.exists()) {
                file.writeBytes(encodePng(bytes, circle) ?: return null)
            }
            val path = file.absolutePath
            avatarCache[key] = path
            path
        } catch (e: Exception) {
            Logger.w(tag = "NucleusNotificationAdapter") {
                "Failed to cache avatar: ${e.message}"
            }
            null
        }
    }

    // A fresh file per photo: macOS moves an attachment's file into its own store.
    private fun photoPath(bytes: ByteArray): String? = try {
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val staleBefore = System.currentTimeMillis() - PHOTO_TTL_MS
        dir.listFiles()?.filter { it.lastModified() < staleBefore }?.forEach { it.delete() }
        encodePng(bytes, circle = false)?.let { png ->
            File(dir, "${UUID.randomUUID()}.png").apply { writeBytes(png) }.absolutePath
        }
    } catch (e: Exception) {
        Logger.w(tag = "NucleusNotificationAdapter") { "Failed to write notification photo: ${e::class.simpleName}" }
        null
    }

    companion object {
        private const val PHOTO_TTL_MS = 24 * 60 * 60 * 1000L

        /**
         * Attempts to construct the adapter and eagerly initialise Nucleus.
         * Returns null if the platform has no notification backend available
         * (e.g. a headless Linux box with no notification daemon) so callers
         * can fall back to KMPNotifier.
         */
        fun createOrNull(): NucleusNotificationAdapter? = try {
            NotificationManager.initialize()
            if (!NotificationManager.isAvailable()) {
                Logger.i(tag = "NucleusNotificationAdapter") {
                    "Nucleus reports no platform dispatcher available — falling back"
                }
                null
            } else {
                NucleusNotificationAdapter()
            }
        } catch (e: Exception) {
            Logger.w(tag = "NucleusNotificationAdapter") {
                "Nucleus initialisation failed: ${e.message}"
            }
            null
        }
    }
}
