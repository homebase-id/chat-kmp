package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import id.homebase.api.browser.DesktopAppFocusManager
import id.homebase.api.file.JvmFileSystemUtil
import io.github.kdroidfilter.nucleus.notification.common.NotificationManager
import io.github.kdroidfilter.nucleus.notification.common.NotificationResult
import io.github.kdroidfilter.nucleus.notification.common.notification
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM-only adapter that wraps the Nucleus cross-platform notification API
 * (Windows toast / macOS user notification / Linux libnotify).
 *
 * Avatar bytes passed in [RichNotificationData.senderImageBytes] are written
 * once per sender-hash to a temp file under the app data directory; Nucleus
 * requires a filesystem path, not in-memory bytes.
 */
internal class NucleusNotificationAdapter private constructor() {

    private val avatarCache = ConcurrentHashMap<String, String>()
    private val cacheDir: File = File(JvmFileSystemUtil.getAppDataDirectory(), "notification-cache").apply {
        if (!exists()) mkdirs()
    }

    fun show(data: RichNotificationData) {
        try {
            val largeImage = data.senderImageBytes?.let(::cachedAvatarPath)
            val result = notification(
                title = data.title,
                message = data.body,
                largeImage = largeImage,
                onActivated = {
                    DesktopAppFocusManager.requestFocus()
                    NotificationClickRouter.onClick(data.payloadData)
                },
                onFailed = {
                    Logger.w(tag = "NucleusNotificationAdapter") {
                        "Nucleus reported delivery failure for notification '${data.title}'"
                    }
                },
            ).send()
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

    private fun cachedAvatarPath(bytes: ByteArray): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
            val key = digest.joinToString("") { "%02x".format(it) }
            avatarCache[key]?.let { existing ->
                if (File(existing).exists()) return existing
            }
            val file = File(cacheDir, "$key.png")
            if (!file.exists()) {
                file.writeBytes(bytes)
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

    companion object {
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
