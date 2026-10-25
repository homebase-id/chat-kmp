package id.homebase.api.youauth

import kotlin.text.iterator

/**
 * Parameters for extending app registration/permissions. These are sent to the /owner/appupdate
 * endpoint.
 */
data class AppAuthorizationExtendParams(
        /** Application ID */
        val appId: String,
        /** JSON-encoded list of missing drives */
        val d: String? = null,
        /** Comma-separated permission keys */
        val p: String? = null,
        /** JSON-encoded circle drives */
        val cd: String? = null,
        /** Return URL for deep linking back to app */
        val returnUrl: String
) {
    /** Convert to query string for URL building. */
    fun toQueryString(): String = buildString {
        append("appId=").append(appId.encodeURLParam())
        d?.let { append("&d=").append(it.encodeURLParam()) }
        p?.let { append("&p=").append(it.encodeURLParam()) }
        cd?.let { append("&cd=").append(it.encodeURLParam()) }
        append("&return=").append(returnUrl.encodeURLParam())
    }

    companion object {
        /**
         * Create extend params from missing permissions configuration.
         *
         * @param appId Application identifier
         * @param drives Missing drive access requests
         * @param circleDrives Optional circle-specific drives
         * @param permissionKeys Missing permission key values
         * @param returnUrl Deep link URL to return to app
         */
        fun create(
            appId: String,
            drives: List<TargetDriveAccessRequest>,
            circleDrives: List<TargetDriveAccessRequest>? = null,
            permissionKeys: List<Int>? = null,
            returnUrl: String
        ): AppAuthorizationExtendParams {
            return AppAuthorizationExtendParams(
                    appId = appId,
                    d =
                            if (drives.isNotEmpty()) TargetDriveAccessRequest.Companion.encodeList(drives)
                            else null,
                    p = permissionKeys?.joinToString(","),
                    cd =
                            circleDrives?.takeIf { it.isNotEmpty() }?.let {
                                TargetDriveAccessRequest.Companion.encodeList(it)
                            },
                    returnUrl = returnUrl
            )
        }
    }
}

/** URL-encode a string parameter. */
private fun String.encodeURLParam(): String {
    return buildString {
        for (char in this@encodeURLParam) {
            when {
                char.isLetterOrDigit() || char in "-_.~" -> append(char)
                char == ' ' -> append("+")
                else -> {
                    val bytes = char.toString().encodeToByteArray()
                    for (byte in bytes) {
                        append('%')
                        append(byte.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0'))
                    }
                }
            }
        }
    }
}
