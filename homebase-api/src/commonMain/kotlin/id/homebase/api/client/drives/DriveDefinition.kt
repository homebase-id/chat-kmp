package id.homebase.homebasekmppoc.prototype.lib.drives

import kotlinx.serialization.Serializable

/**
 * Represents a drive definition with metadata and configuration.
 *
 * Ported from TypeScript DriveDefinition interface
 */
@Serializable
data class DriveDefinition(
    val driveId: String,
    val name: String,
    val targetDriveInfo: id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive,
    val metadata: String,
    val allowAnonymousReads: Boolean,
    val allowSubscriptions: Boolean,
    val ownerOnly: Boolean,
    val attributes: Map<String, String>
)
