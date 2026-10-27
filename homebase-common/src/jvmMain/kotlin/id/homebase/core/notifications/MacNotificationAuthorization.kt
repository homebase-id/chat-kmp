package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import io.github.kdroidfilter.nucleus.notification.AuthorizationOption
import io.github.kdroidfilter.nucleus.notification.AuthorizationStatus
import io.github.kdroidfilter.nucleus.notification.NotificationCenter
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** UNUserNotificationCenter drops every notification until the user has authorised the bundle. */
internal object MacNotificationAuthorization {

    val applies: Boolean
        get() = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true) &&
                !isMacOsRunningUnbundled()

    /** Prompts only while undecided; afterwards macOS answers with the stored decision. */
    fun request(onResult: (Boolean) -> Unit = {}) {
        if (!applies) return onResult(true)
        NotificationCenter.requestAuthorization(
            setOf(AuthorizationOption.ALERT, AuthorizationOption.SOUND, AuthorizationOption.BADGE),
        ) { granted, error ->
            Logger.i(tag = "MacNotificationAuthorization") {
                "authorization granted=$granted${error?.let { " error=$it" }.orEmpty()}"
            }
            onResult(granted)
        }
    }

    suspend fun isGranted(): Boolean {
        if (!applies) return true
        return suspendCancellableCoroutine { cont ->
            NotificationCenter.getNotificationSettings { settings ->
                val status = settings.authorizationStatus
                cont.resume(status == AuthorizationStatus.AUTHORIZED || status == AuthorizationStatus.PROVISIONAL)
            }
        }
    }
}
