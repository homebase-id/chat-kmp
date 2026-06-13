package id.homebase.api.client.connections

import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.common.OdinId
import id.homebase.api.youauth.DrivePermissionSet
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName

@Serializable
data class OdinIdRequest(
    val odinId: OdinId
)

@Serializable
data class AddCircleMembershipRequest(
    val odinId: OdinId,
    val circleId: Uuid
)

@Serializable
data class RevokeCircleMembershipRequest(
    val odinId: OdinId,
    val circleId: Uuid
)

@Serializable
data class GetCircleMembersRequest(
    val circleId: Uuid
)

/**
 * One circle plus its members, from `GET /api/v2/connections/circles/with-members`.
 * The server bundles members so listing circles needs a single round-trip.
 */
@Serializable
data class CircleWithMembers(
    val circle: RedactedCircleDefinition,
    /** Member identities, as domain strings (e.g. "sam.dotyou.cloud"). */
    val members: List<OdinId> = emptyList(),
)

/**
 * Redacted circle definition. GUID ids arrive as 32-char "N"-format strings (no
 * hyphens), so they are modeled as [String], not Uuid. [created]/[lastUpdated] are
 * epoch-millis. Optional fields may be absent on permission-only circles.
 */
@Serializable
data class RedactedCircleDefinition(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val disabled: Boolean = false,
    val created: Long = 0,
    val lastUpdated: Long = 0,
    val permissions: RedactedPermissionSet? = null,
    val driveGrants: List<RedactedCircleDriveGrant>? = null,
)

@Serializable
data class RedactedCircleDriveGrant(
    val permissionedDrive: RedactedPermissionedDrive? = null,
)

@Serializable
data class RedactedPermissionedDrive(
    val drive: RedactedTargetDrive? = null,
    /** DrivePermission flags as a camelCase string, e.g. "read" or "read,write". */
    val permission: String? = null,
)

@Serializable
data class RedactedTargetDrive(
    val alias: String? = null,
    val type: String? = null,
)

@Serializable
data class CursoredResult<T>(
    val cursor: String? = null,
    val results: List<T> = emptyList()
)

@Serializable
data class RedactedIdentityConnectionRegistration(
    val odinId: OdinId,
    val status: ConnectionStatus,
    val accessGrant: RedactedAccessExchangeGrant? = null,
    val created: Long,
    val lastUpdated: Long,
    val originalContactData: ContactRequestData? = null,
    val introducerOdinId: OdinId? = null,
    val connectionRequestOrigin: ConnectionRequestOrigin,
    val hasVerificationHash: Boolean,
    val rku: Boolean
)

// ------------------------------------------------------------
// ACCESS GRANTS
// ------------------------------------------------------------

@Serializable
data class RedactedAccessExchangeGrant(
    val isRevoked: Boolean,
    val circleGrants: List<RedactedCircleGrant> = emptyList(),
    val appGrants: Map<Uuid, List<RedactedAppCircleGrant>> = emptyMap()
)

@Serializable
data class RedactedCircleGrant(
    val circleId: Uuid,
    val permissionSet: PermissionSet? = null,
    val driveGrants: List<RedactedDriveGrant> = emptyList()
)

@Serializable
data class RedactedAppCircleGrant(
    val appId: Uuid,
    val circleId: Uuid,
    val permissionSet: PermissionSet? = null,
    val driveGrants: List<RedactedDriveGrant> = emptyList()
)

@Serializable
data class RedactedDriveGrant(
    val permissionedDrive: PermissionedDrive,
    val hasStorageKey: Boolean
)

// ------------------------------------------------------------
// PERMISSIONS
// ------------------------------------------------------------

@Serializable
data class PermissionSet(
    val keys: List<Int> = emptyList()
)

// ------------------------------------------------------------
// DRIVE MODELS
// ------------------------------------------------------------

@Serializable
data class PermissionedDrive(
    val drive: TargetDrive,
    val permission: DrivePermissionSet
)

// ------------------------------------------------------------
// ENUMS (lowercase serialized)
// ------------------------------------------------------------

@Serializable
enum class ConnectionStatus {
    @SerialName("unknown")
    Unknown,

    @SerialName("none")
    Pending,

    @SerialName("connected")
    Connected,

    @SerialName("blocked")
    Blocked,
}

@Serializable
enum class ConnectionRequestOrigin {

    @SerialName("none")
    None,

    @SerialName("identityOwner")
    IdentityOwner,

    @SerialName("introduction")
    Introduction,

    @SerialName("identityOwnerApp")
    IdentityOwnerApp
}

// ------------------------------------------------------------
// OTHER SUPPORT MODELS
// ------------------------------------------------------------

@Serializable
data class ContactRequestData(
    val placeholder: String? = null
)

// ------------------------------------------------------------
// VERIFICATION / TROUBLESHOOTING
// ------------------------------------------------------------

@Serializable
data class IcrVerificationResult(
    val success: Boolean,
    val message: String? = null
)

@Serializable
data class IcrTroubleshootingInfo(
    val icr: RedactedIdentityConnectionRegistration,
    val circles: List<CircleInfo> = emptyList()
)

@Serializable
data class CircleInfo(
    val circleDefinitionId: Uuid,
    val circleDefinitionName: String,
    val circleDefinitionDriveGrantCount: Int,
    val analysis: CircleAnalysis
)

@Serializable
data class CircleAnalysis(
    val summary: String,
    val isCircleMember: Boolean,
    val permissionKeysAreValid: Boolean,
    val expectedPermissionKeys: RedactedPermissionSet,
    val actualPermissionKeys: RedactedPermissionSet,
    val driveGrantAnalysis: List<DriveGrantInfo> = emptyList()
)

@Serializable
data class RedactedPermissionSet(
    val keys: List<Int> = emptyList()
)

@Serializable
data class DriveGrantInfo(
    val driveName: String,
    val driveGrantIsValid: Boolean,
    val driveIsGranted: Boolean,
    val expectedDrivePermissionSet: Int,
    val actualDrivePermissionSet: Int,
    val encryptedKeyLength: Int,
    val targetDrive: TargetDrive,
    val hasValidEncryptionKey: Boolean,
    val DrivePermissionSetIsValid: Boolean
)