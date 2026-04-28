package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import com.mmk.kmpnotifier.notification.NotifierManager

/**
 * Desktop displayer — prefers the Nucleus cross-platform notification backend
 * (Windows toast / macOS user notification / Linux libnotify). Falls back to
 * KMPNotifier's LocalNotifier if Nucleus is unavailable on this platform
 * (e.g. headless Linux with no notification daemon).
 */
actual class RichNotificationDisplayer actual constructor() {

    // macOS UNUserNotificationCenter requires a real .app bundle; from an unbundled
    // JVM (Android Studio JBR, gradle :desktopApp:run, system JDK) it raises an
    // NSException that aborts the JVM via SIGABRT — Kotlin try/catch can't intercept
    // it. Skip the native call in that environment.
    private val disabled: Boolean = isMacOsRunningUnbundled().also { unbundled ->
        if (unbundled) {
            Logger.w(tag = "RichNotificationDisplayer") {
                "macOS unbundled JVM detected (java.home=${System.getProperty("java.home")}); " +
                        "skipping native notification display to avoid an UNUserNotificationCenter " +
                        "NSException that would abort the JVM. Run a packaged distributable " +
                        "(./gradlew desktopApp:createDistributable) to test notifications."
            }
        }
    }

    private val nucleus: NucleusNotificationAdapter? =
        if (disabled) null else NucleusNotificationAdapter.createOrNull()

    actual fun show(data: RichNotificationData) {
        if (disabled) return
        val adapter = nucleus
        if (adapter != null) {
            adapter.show(data)
            return
        }
        val notifier = NotifierManager.getLocalNotifier()
        notifier.notify(
            id = data.notificationId,
            title = data.title,
            body = data.body,
            payloadData = data.payloadData,
        )
    }
}

private fun isMacOsRunningUnbundled(): Boolean {
    val osName = System.getProperty("os.name") ?: ""
    if (!osName.contains("Mac", ignoreCase = true)) return false
    // Packaged HomebaseChat / HomebaseChatDev launchers put the JRE inside the .app
    // bundle, so java.home contains the bundle name. Anything else (IDE JBR, gradle,
    // system JDK) is unbundled and macOS native notifications will crash.
    val javaHome = System.getProperty("java.home") ?: ""
    return !javaHome.contains("HomebaseChat", ignoreCase = true)
}
