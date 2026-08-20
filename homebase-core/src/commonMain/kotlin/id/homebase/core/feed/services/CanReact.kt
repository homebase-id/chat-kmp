package id.homebase.core.feed.services

import id.homebase.api.common.OdinId
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DriveReference
import id.homebase.api.youauth.SecurityContext
import id.homebase.api.youauth.getUniqueDrivesWithHighestPermission
import kotlin.uuid.Uuid

sealed interface CanReact {

    data object All : CanReact

    /** The channel drive carries React but not Comment. */
    data object EmojiOnly : CanReact

    /** The channel drive carries Comment but not React. */
    data object CommentOnly : CanReact

    data class Denied(val reason: DenyReason) : CanReact

    val allowsEmoji: Boolean get() = this is All || this is EmojiOnly

    val allowsComment: Boolean get() = this is All || this is CommentOnly
}

enum class DenyReason {
    /** No session at all. */
    NotAuthenticated,

    /** Authenticated, but the channel drive grants neither React nor Comment. */
    NotAuthorized,

    /** The author switched interaction off (`reactAccess: false`). */
    DisabledOnPost,

    /** The security context could not be resolved, so permissions are unknown. */
    Unknown,
}

// Only grants naming BOTH [channelDriveAlias] and [channelDriveType] count. Grants for the same drive from
// several permission groups are merged before React/Comment are read.
// One deliberate deviation from the web: a null [securityContext] yields Unknown rather than NotAuthorized. In
// JS an unresolved context optional-chains to an empty grant list, making it indistinguishable from "granted
// nothing"; separating them lets the caller retry a failed fetch instead of showing a hard denial.
fun evaluateCanReact(
    securityContext: SecurityContext?,
    channelDriveAlias: Uuid,
    postAuthor: OdinId?,
    loggedInIdentity: OdinId?,
    reactAccess: ReactAccess,
    isAuthenticated: Boolean,
    isOwner: Boolean,
    channelDriveType: Uuid = FeedProtocol.ChannelDriveType,
): CanReact {
    if (!isAuthenticated && !isOwner) return CanReact.Denied(DenyReason.NotAuthenticated)

    if (postAuthor == loggedInIdentity) return CanReact.All

    if (securityContext == null) return CanReact.Denied(DenyReason.Unknown)

    val permissions = securityContext.permissionContext.permissionGroups
        .flatMap { it.driveGrants.orEmpty() }
        .filter { it.permissionedDrive.drive.matches(channelDriveAlias, channelDriveType) }
        .let(::getUniqueDrivesWithHighestPermission)
        .flatMap { it.permissionedDrive.permission }

    val hasReact = DrivePermission.React in permissions
    val hasComment = DrivePermission.Comment in permissions

    if (!hasReact && !hasComment) return CanReact.Denied(DenyReason.NotAuthorized)

    // Only the explicit "off" disables a post; EmojiOnly/CommentOnly are rendering hints applied further down.
    if (reactAccess == ReactAccess.None) return CanReact.Denied(DenyReason.DisabledOnPost)

    if (!hasReact) return CanReact.CommentOnly
    if (!hasComment) return CanReact.EmojiOnly
    return CanReact.All
}

// Drive ids arrive from the API in either GUID spelling; compare them parsed so a hyphen-less alias still
// matches. An unparseable id never matches.
private fun DriveReference.matches(alias: Uuid, type: Uuid): Boolean =
    alias == this.alias.toUuidOrNull() && type == this.type.toUuidOrNull()

internal fun String.toUuidOrNull(): Uuid? = runCatching { Uuid.parse(this) }.getOrNull()
