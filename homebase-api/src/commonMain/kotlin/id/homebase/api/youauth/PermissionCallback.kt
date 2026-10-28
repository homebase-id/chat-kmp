package id.homebase.api.youauth

import id.homebase.api.client.eventbus.BackendEvent
import io.ktor.http.Url

/**
 * The owner console's return to Chat's permission callback: whether the owner canceled, and which mini-app's
 * request it answers ([REQUESTER_PARAM], which the return URL carries; null from a URL built without it).
 */
data class PermissionCallback(val canceled: Boolean, val requester: String?) {

    val event: BackendEvent
        get() = if (canceled) BackendEvent.PermissionsExtensionCanceled(requester) else BackendEvent.PermissionsExtensionReturned(requester)

    companion object {
        const val REQUESTER_PARAM = "for"

        fun parse(url: String): PermissionCallback {
            val params = Url(url).parameters
            val status = params["status"]
            return PermissionCallback(
                canceled = status.equals("canceled", ignoreCase = true) || status.equals("cancelled", ignoreCase = true),
                requester = params[REQUESTER_PARAM],
            )
        }
    }
}
