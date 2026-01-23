package id.homebase.homebasekmppoc.lib.youauth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Security context returned by the API, containing permission information for the current session.
 */
@Serializable
data class SecurityContext(val caller: CallerContext, val permissionContext: PermissionContext)

/** Information about the caller of the API. */
@Serializable
data class CallerContext(
        val odinId: String? = null,
        val securityLevel: String,
        /** Whether the caller has been granted the connected identities system circle. */
        @SerialName("isGrantedConnectedIdentitiesSystemCircle")
        val isGrantedConnectedIdentitiesSystemCircle: Boolean = false
)

/** Container for permission groups granted to the caller. */
@Serializable data class PermissionContext(val permissionGroups: List<PermissionGroup>)

/** A permission group containing drive grants and permission sets. */
@Serializable
data class PermissionGroup(
        val driveGrants: List<DriveGrant>? = null,
        val permissionSet: PermissionSet? = null
)

/** A grant for a specific drive with permissions. */
@Serializable data class DriveGrant(val permissionedDrive: PermissionedDrive)

/**
 * A drive reference with permissions. The permission field may be a string or list from the API.
 */
@Serializable
data class PermissionedDrive(
        val drive: DriveReference,
        /**
         * Permissions for this drive. After parsing, this contains the permission types. The raw
         * API may return a string like "ReadWrite" which needs conversion.
         */
        val permission: List<DrivePermissionType>
)

/** A reference to a drive by alias and type. */
@Serializable data class DriveReference(val alias: String, val type: String)

/** A set of permission keys granted. */
@Serializable data class PermissionSet(val keys: List<Int> = emptyList())

// Circle IDs used for connected identities
const val AUTO_CONNECTIONS_CIRCLE_ID = "auto_connections"
const val CONFIRMED_CONNECTIONS_CIRCLE_ID = "confirmed_connections"
