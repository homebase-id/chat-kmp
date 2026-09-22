package id.homebase.api.client.drives

import kotlinx.serialization.Serializable
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.common.OdinId

/**
 * Server metadata
 * Ported from C# Odin.Services.Drives.DriveCore.Storage.ServerMetadata
 *
 * Note: Simplified version - some complex nested types are stubbed
 */
@Serializable
data class ServerMetadata(
    val accessControlList: AccessControlList? = null,
    //@Deprecated("Use allowDistribution instead")
    //val doNotIndex: Boolean = false, <-- MS if it's deprecated, let's try not to use it
    val allowDistribution: Boolean = false,
    val fileSystemType: FileSystemType = FileSystemType.Standard,
    val fileByteCount: Long = 0,
    val originalRecipientCount: Int = 0,
    val transferHistory: RecipientTransferHistory? = null
)

/**
 * Stub types - implement as needed based on your requirements
 */
@Serializable
data class AccessControlList(
    val requiredSecurityGroup: String? = null,
    val circleIdList: List<String>? = null,
    val odinIdList: List<OdinId>? = null
    // Add fields as needed from the C# AccessControlList
)

// Odin's SystemCircleConstants.ConfirmedConnectionsCircleId, granted to every owner-approved connection.
private const val CONFIRMED_CONNECTIONS_SYSTEM_CIRCLE = "bb2683fa402aff866e771a6495765a15"

/**
 * Whether [viewer] can read a file with this ACL, mirroring Odin's DriveAclAuthorizationService for
 * an anonymous visitor ([ProfileVisibility.ANONYMOUS]), a logged-in stranger
 * ([ProfileVisibility.AUTHENTICATED]) or a plain connection whose only circle is the system
 * confirmed-connections one ([ProfileVisibility.CONNECTED]). A missing ACL or an unknown group is
 * visible to the owner only.
 */
fun AccessControlList?.isVisibleTo(viewer: ProfileVisibility): Boolean {
    if (viewer == ProfileVisibility.OWNER) return true
    if (this == null || !odinIdList.isNullOrEmpty()) return false
    val required = when (requiredSecurityGroup?.lowercase()) {
        "anonymous" -> ProfileVisibility.ANONYMOUS
        "authenticated" -> ProfileVisibility.AUTHENTICATED
        "connected", "autoconnected" -> ProfileVisibility.CONNECTED
        else -> return false
    }
    if (viewer < required) return false
    val circles = circleIdList.orEmpty()
    return circles.isEmpty() ||
        viewer == ProfileVisibility.CONNECTED &&
        circles.any { it.replace("-", "").equals(CONFIRMED_CONNECTIONS_SYSTEM_CIRCLE, ignoreCase = true) }
}

private fun securityRank(group: String?): Int = when (group?.lowercase()) {
    "owner" -> 1
    "autoconnected" -> 2
    "connected" -> 3
    "authenticated" -> 4
    "anonymous" -> 5
    else -> 0
}

/** odin-js `compareAcl`: owner-only first, then narrower groups, then files limited to circles or identities. */
val aclMostRestrictiveFirst: Comparator<AccessControlList> =
    compareBy<AccessControlList> { securityRank(it.requiredSecurityGroup) }
        .thenBy { it.circleIdList.isNullOrEmpty() }
        .thenBy { it.circleIdList?.size ?: 0 }
        .thenBy { it.odinIdList.isNullOrEmpty() }
        .thenBy { it.odinIdList?.size ?: 0 }

@Serializable
data class RecipientTransferHistory(
    val summary: TransferHistorySummary
)

@Serializable
data class TransferHistorySummary(
    val totalInOutbox: Int,
    val totalFailed: Int,
    val totalDelivered: Int,
    val totalReadByRecipient: Int
)
