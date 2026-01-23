package id.homebase.homebasekmppoc.prototype.lib.drives

import kotlinx.serialization.Serializable

/**
 * Server metadata
 * Ported from C# Odin.Services.Drives.DriveCore.Storage.ServerMetadata
 *
 * Note: Simplified version - some complex nested types are stubbed
 */
@Serializable
data class ServerMetadata(
    val accessControlList: id.homebase.homebasekmppoc.prototype.lib.drives.AccessControlList? = null,
    //@Deprecated("Use allowDistribution instead")
    //val doNotIndex: Boolean = false, <-- MS if it's deprecated, let's try not to use it
    val allowDistribution: Boolean = false,
    val fileSystemType: id.homebase.homebasekmppoc.prototype.lib.drives.FileSystemType = _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.FileSystemType.Standard,
    val fileByteCount: Long = 0,
    val originalRecipientCount: Int = 0,
    val transferHistory: id.homebase.homebasekmppoc.prototype.lib.drives.RecipientTransferHistory? = null
)

/**
 * Stub types - implement as needed based on your requirements
 */
@Serializable
data class AccessControlList(
    val requiredSecurityGroup: String? = null,
    val circleIdList: List<String>? = null,
    val odinIdList: List<String>? = null
    // Add fields as needed from the C# AccessControlList
)

@Serializable
data class RecipientTransferHistory(
    val recipients: List<String>? = null
    // Add fields as needed
)
