package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.core.config.LabeledDrive
import id.homebase.core.config.chatLabeledDrive
import id.homebase.core.config.contactLabeledDrive
import id.homebase.core.config.locationLabeledDrive
import id.homebase.core.config.momentsLabeledDrive
import id.homebase.core.config.stickerLabeledDrive
import id.homebase.core.config.vaultLabeledDrive

/** One drive a circle grants access to. [label] is null when the drive alias doesn't match any
 *  of this app's own known drives (a system drive, another app's drive, or a placeholder GUID
 *  pending server provisioning) — the caller falls back to a generic "unknown drive" string. */
data class CircleDriveUi(val label: String?, val permission: String)

private fun String.normalizedGuid(): String = replace("-", "").lowercase()

/**
 * This app's own known drives, keyed by normalized (no-hyphen, lowercase) alias, for matching
 * against a circle's drive grants. `RedactedTargetDrive.alias` has no prior consumer in this
 * codebase (unlike `RedactedCircleGrant.circleId`, which needed `GuidIdUuidSerializer` because
 * the server's GuidId type always serializes hyphen-less) — its wire format is unconfirmed, so
 * both sides are normalized defensively before comparing rather than assuming either shape.
 */
private val knownDrives: List<LabeledDrive> = listOf(
    chatLabeledDrive,
    contactLabeledDrive,
    locationLabeledDrive,
    vaultLabeledDrive,
    stickerLabeledDrive,
    momentsLabeledDrive,
)

private val knownDrivesByAlias: Map<String, String> by lazy {
    knownDrives.associate { it.drive.alias.toString().normalizedGuid() to it.label }
}

/** Resolves [circle]'s drive grants to friendly labels where recognized. Order follows the
 *  server's own driveGrants order; permission is the raw camelCase string (e.g. "read" or
 *  "read,write") since it's already a stable, small vocabulary not worth re-parsing here. */
fun resolveCircleDrives(circle: RedactedCircleDefinition): List<CircleDriveUi> =
    circle.driveGrants.orEmpty().mapNotNull { grant ->
        val permissionedDrive = grant.permissionedDrive ?: return@mapNotNull null
        val alias = permissionedDrive.drive?.alias ?: return@mapNotNull null
        val permission = permissionedDrive.permission ?: return@mapNotNull null
        CircleDriveUi(label = knownDrivesByAlias[alias.normalizedGuid()], permission = permission)
    }
