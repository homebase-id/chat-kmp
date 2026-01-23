package id.homebase.homebasekmppoc.lib.youauth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * App-level authorization parameters sent as part of the permission_request. Uses short keys
 * matching the API specification.
 */
@Serializable
data class AppAuthorizationParams(
        /** App name */
        @SerialName("n") val name: String,

        /** App ID */
        @SerialName("appId") val appId: String,

        /** Client friendly name (e.g., "Chrome | macOS") */
        @SerialName("fn") val friendlyName: String,

        /** JSON-encoded list of drive access requests */
        @SerialName("d") val drives: String? = null,

        /** Circle drives JSON */
        @SerialName("cd") val circleDrives: String? = null,

        /** Circle IDs (comma-separated) */
        @SerialName("c") val circles: String? = null,

        /** Permission keys (comma-separated) */
        @SerialName("p") val permissions: String? = null,

        /** Circle permission keys (comma-separated) */
        @SerialName("cp") val circlePermissions: String? = null,

        /** Return URL */
        @SerialName("return") val returnUrl: String,

        /** Optional origin/host */
        @SerialName("o") val origin: String? = null
) {
        /** Convert to JSON string for embedding in permission_request. */
        fun toJson(): String = Json.encodeToString(serializer(), this)

        companion object {
                /** Create app auth params with drive access requests. */
                fun create(
                        appName: String,
                        appId: String,
                        friendlyName: String,
                        drives: List<TargetDriveAccessRequest> = emptyList(),
                        circleDrives: List<TargetDriveAccessRequest>? = null,
                        circles: List<String>? = null,
                        permissions: List<Int>? = null,
                        circlePermissions: List<Int>? = null,
                        returnUrl: String,
                        origin: String? = null
                ): AppAuthorizationParams {
                        return AppAuthorizationParams(
                                name = appName,
                                appId = appId,
                                friendlyName = friendlyName,
                                // API requires 'd' field to always be present, even if empty array
                                drives =
                                        if (drives.isNotEmpty())
                                                TargetDriveAccessRequest.encodeList(drives)
                                        else "[]",
                                circleDrives =
                                        circleDrives?.takeIf { it.isNotEmpty() }?.let {
                                                TargetDriveAccessRequest.encodeList(it)
                                        },
                                circles = circles?.joinToString(","),
                                permissions = permissions?.joinToString(","),
                                circlePermissions = circlePermissions?.joinToString(","),
                                returnUrl = returnUrl,
                                origin = origin
                        )
                }
        }
}
