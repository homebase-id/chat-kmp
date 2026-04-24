package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import id.homebase.api.browser.DesktopAppFocusManager
import id.homebase.api.file.JvmFileSystemUtil
import io.github.kdroidfilter.nucleus.notification.common.NotificationManager
import io.github.kdroidfilter.nucleus.notification.common.NotificationResult
import io.github.kdroidfilter.nucleus.notification.common.notification
import io.github.kdroidfilter.nucleus.notification.windows.ShortcutPolicy
import io.github.kdroidfilter.nucleus.notification.windows.WindowsNotificationCenter
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val DESKTOP_AUMID = "id.homebase.chat.desktop"
private const val DESKTOP_APP_NAME = "Homebase Chat"

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
            initializeWindowsIfApplicable()
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

        /**
         * Windows toasts require an installed app whose AUMID is bound to a Start Menu
         * shortcut. Unpackaged Java apps (e.g. dev-mode `./gradlew desktopApp:run`) have
         * no such shortcut, so Nucleus's default policy (`REQUIRE_NO_CREATE` in dev
         * mode) lets `send()` succeed while WinRT silently drops the toast.
         *
         * We force [ShortcutPolicy.REQUIRE_CREATE] with an explicit AUMID so a Start
         * Menu shortcut is created once on first run; subsequent runs reuse it and
         * toasts render.
         *
         * Harmless no-op on macOS/Linux — [WindowsNotificationCenter.isAvailable]
         * returns false when the native bridge hasn't loaded.
         */
        private fun initializeWindowsIfApplicable() {
            val osName = System.getProperty("os.name")?.lowercase().orEmpty()
            if (!osName.contains("windows")) return
            try {
                if (!WindowsNotificationCenter.isAvailable) {
                    Logger.w(tag = "NucleusNotificationAdapter") {
                        "Windows native bridge not available — skipping explicit AUMID init"
                    }
                    return
                }
                WindowsNotificationCenter.initialize(
                    aumid = DESKTOP_AUMID,
                    appName = DESKTOP_APP_NAME,
                    shortcutPolicy = ShortcutPolicy.REQUIRE_CREATE,
                )
            } catch (e: Throwable) {
                Logger.w(tag = "NucleusNotificationAdapter") {
                    "WindowsNotificationCenter.initialize threw: ${e.message}"
                }
            }
        }
    }
}
