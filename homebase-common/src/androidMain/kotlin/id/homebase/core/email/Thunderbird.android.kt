package id.homebase.core.email

import android.content.Intent
import co.touchlab.kermit.Logger
import id.homebase.api.ActivityProvider

/**
 * Android launches a known app by package name. Note that on API 30+ the package must also be
 * listed in the manifest's <queries> block, or it is invisible to us and reads as not installed.
 */
actual fun canLaunchMailClient(client: MailClientDescriptor): Boolean =
    client.androidPackage != null

actual suspend fun launchMailClient(client: MailClientDescriptor): Boolean {
    val packageName = client.androidPackage ?: return false
    val context = runCatching { ActivityProvider.requireApplicationContext() }.getOrNull() ?: return false

    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent == null) {
        // Not installed, or not visible to us because it is missing from <queries>.
        Logger.d(tag = "MailClient") { "${client.displayName} ($packageName) is not launchable here" }
        return false
    }

    return runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrElse { e ->
        Logger.w(tag = "MailClient", throwable = e) { "Could not start ${client.displayName}" }
        false
    }
}
