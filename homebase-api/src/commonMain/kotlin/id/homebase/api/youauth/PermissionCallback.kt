package id.homebase.api.youauth

import id.homebase.api.client.eventbus.BackendEvent
import co.touchlab.kermit.Logger
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

/**
 * The owner console's return to Chat's permission callback: whether the owner canceled, and which mini-app's
 * request it answers ([REQUESTER_PARAM], which the return URL carries; null from a URL built without it).
 */
data class PermissionCallback(val canceled: Boolean, val requester: String?) {

    val event: BackendEvent
        get() = if (canceled) BackendEvent.PermissionsExtensionCanceled(requester) else BackendEvent.PermissionsExtensionReturned(requester)

    companion object {
        internal const val REQUESTER_PARAM = "for"

        /** Never throws: anything can open this URL, and a malformed one counts as an untagged return. */
        fun parse(url: String): PermissionCallback {
            val params = try {
                Url(url).parameters
            } catch (e: Exception) {
                Logger.w(throwable = e, tag = "PermissionCallback") { "Unreadable permission callback URL" }
                return PermissionCallback(canceled = false, requester = null)
            }
            val status = params["status"]
            return PermissionCallback(
                canceled = status.equals("canceled", ignoreCase = true) || status.equals("cancelled", ignoreCase = true),
                requester = params[REQUESTER_PARAM],
            )
        }
    }
}

/** Appends one query parameter; the return URLs here carry no fragment. */
internal fun String.withQueryParam(name: String, value: String): String =
    this + (if ('?' in this) "&" else "?") + name + "=" + value.encodeURLParameter()
