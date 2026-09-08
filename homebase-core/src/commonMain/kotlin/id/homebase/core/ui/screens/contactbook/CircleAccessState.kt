package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.connections.RedactedCircleGrant
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.api.youauth.DrivePermission

/**
 * Whether a circle membership is actually delivering the access it appears to.
 *
 * Membership alone has never been the whole truth — a read grant needs a storage key to go with
 * it, and until one lands the member can see the file list and decrypt nothing. Rendering a flat
 * list of circle names states the grant and hides the gap.
 */
enum class CircleAccessState {
    /** Granted, and every read grant has the key to go with it. */
    Active,

    /** Granted, but a read grant is missing its storage key — they cannot read it yet. */
    Incomplete,

    /** Requested by an app, not yet landed. Takes effect the next time they connect. */
    Pending,
}

/**
 * True when this grant claims read access it cannot deliver.
 *
 * A missing storage key on write or react is normal and must never be flagged: a deposit needs no
 * key to seal to. Only read is a claim about seeing, and only read needs the key.
 */
private fun RedactedCircleGrant.hasUnreadableReadGrant(): Boolean =
    driveGrants.any { grant ->
        !grant.hasStorageKey &&
            grant.permissionedDrive.permission.values.contains(DrivePermission.Read)
    }

/**
 * The state of [circleId] for this contact, or null when the circle is neither granted nor
 * pending — i.e. they are simply not in it.
 *
 * Pending is checked first: an id can appear in both while a deposit converts, and the honest
 * answer during that window is the one that has not taken effect yet.
 */
fun RedactedIdentityConnectionRegistration.circleAccessState(circleId: String): CircleAccessState? {
    val grant = accessGrant ?: return null
    val id = circleId.lowercase()

    if (grant.pendingCircleIds.any { it.toString().replace("-", "").lowercase() == id }) {
        return CircleAccessState.Pending
    }

    val circleGrant = grant.circleGrants
        .firstOrNull { it.circleId.toHexString().lowercase() == id }
        ?: return null

    return if (circleGrant.hasUnreadableReadGrant()) CircleAccessState.Incomplete
    else CircleAccessState.Active
}

/** True when the contact's access has been switched off wholesale, circles notwithstanding. */
fun RedactedIdentityConnectionRegistration.isAccessRevoked(): Boolean =
    accessGrant?.isRevoked == true
