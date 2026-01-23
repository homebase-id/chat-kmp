package id.homebase.homebasekmppoc.lib.youauth

import co.touchlab.kermit.Logger
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.api.youauth.getUniqueDrivesWithHighestPermission

/** Configuration for checking missing permissions. */
data class PermissionExtensionConfig(
    val appId: String,
    val appName: String,
    val drives: List<TargetDriveAccessRequest>,
    val circleDrives: List<TargetDriveAccessRequest>? = null,
    val permissions: List<AppPermissionType>,
    val needsAllConnected: Boolean = false,
    val returnUrl: String
)

/** Result of missing permission check. */
data class MissingPermissionsResult(
    val missingDrives: List<TargetDriveAccessRequest>,
    val missingPermissions: List<AppPermissionType>,
    val missingAllConnectedCircle: Boolean,
    val extendPermissionUrl: String
) {
    val hasMissingPermissions: Boolean
        get() =
            missingDrives.isNotEmpty() ||
                    missingPermissions.isNotEmpty() ||
                    missingAllConnectedCircle
}

/**
 * Manager for detecting and handling missing app permissions.
 *
 * This is the Kotlin equivalent of the useMissingPermissions hook.
 */
class PermissionExtensionManager(
    private val securityContextProvider: SecurityContextProvider,
    private val hostIdentity: String
) {
    /**
     * Check if the app is missing any required permissions.
     *
     * @param config Configuration with required drives and permissions
     * @return MissingPermissionsResult if there are missing permissions, null if all granted
     */
    suspend fun getMissingPermissions(
        config: PermissionExtensionConfig
    ): MissingPermissionsResult? {
        val context = securityContextProvider.getSecurityContext()
        if (context == null) {
            Logger.w(TAG) { "Could not fetch security context" }
            return null
        }

        // Get all drive grants from permission groups
        val driveGrants =
            context.permissionContext.permissionGroups.flatMap { group ->
                group.driveGrants ?: emptyList()
            }
        val uniqueDriveGrants = getUniqueDrivesWithHighestPermission(driveGrants)

        // Get all permission keys from permission groups
        val permissionKeys =
            context.permissionContext.permissionGroups.flatMap { group ->
                group.permissionSet?.keys ?: emptyList()
            }

        // Find missing drives
        val missingDrives =
            config.drives.filter { requestedDrive ->
                val matchingGrants =
                    uniqueDriveGrants.filter { grant ->
                        drivesEqual(
                            grant.permissionedDrive.drive,
                            requestedDrive
                        )
                    }

                val requestingPermission =
                    requestedDrive.permissions.sumOf { it.value }
                val hasAccess =
                    matchingGrants.any { grant ->
                        val allPermissions =
                            grant.permissionedDrive.permission.sumOf {
                                it.value
                            }
                        allPermissions >= requestingPermission
                    }

                !hasAccess
            }

        // Find missing app permissions
        val missingPermissions =
            config.permissions.filter { permission ->
                !permissionKeys.contains(permission.value)
            }

        // Check for connected circle grant
        val hasAllConnectedCircle = context.caller.isGrantedConnectedIdentitiesSystemCircle
        val missingAllConnectedCircle = config.needsAllConnected && !hasAllConnectedCircle

        // If nothing is missing, return null
        if (missingDrives.isEmpty() &&
            missingPermissions.isEmpty() &&
            !missingAllConnectedCircle
        ) {
            return null
        }

        // Build the extend permission URL
        val extendPermissionUrl =
            getExtendPermissionUrl(
                host = hostIdentity,
                appId = config.appId,
                missingDrives = missingDrives,
                circleDrives = config.circleDrives,
                missingPermissions = missingPermissions.map { it.value },
                needsAllConnected = missingAllConnectedCircle,
                returnUrl = config.returnUrl
            )

        return MissingPermissionsResult(
            missingDrives = missingDrives,
            missingPermissions = missingPermissions,
            missingAllConnectedCircle = missingAllConnectedCircle,
            extendPermissionUrl = extendPermissionUrl
        )
    }

    /** Build the URL for extending app permissions. */
    private fun getExtendPermissionUrl(
        host: String,
        appId: String,
        missingDrives: List<TargetDriveAccessRequest>,
        circleDrives: List<TargetDriveAccessRequest>?,
        missingPermissions: List<Int>,
        needsAllConnected: Boolean,
        returnUrl: String
    ): String {
        val params =
            AppAuthorizationExtendParams.create(
                appId = appId,
                drives = missingDrives,
                circleDrives = circleDrives,
                permissionKeys = missingPermissions.takeIf { it.isNotEmpty() },
                needsAllConnectedOrCircleIds = needsAllConnected,
                returnUrl = returnUrl
            )

        return "https://$host/owner/appupdate?${params.toQueryString()}"
    }

    companion object {
        private const val TAG = "PermissionExtensionManager"

        /**
         * Create a PermissionExtensionManager from an OdinClient.
         */
        fun create(
            securityContextProvider: SecurityContextProvider,
            hostIdentity: String
        ): PermissionExtensionManager {
            return PermissionExtensionManager(
                securityContextProvider = securityContextProvider,
                hostIdentity = hostIdentity
            )
        }
    }
}

/** Check if two drives are equal by comparing alias and type. */
fun drivesEqual(drive: DriveReference, request: TargetDriveAccessRequest): Boolean {
    return drive.alias == request.alias && drive.type == request.type
}
