package id.homebase.core.feed.services

import id.homebase.api.common.OdinId
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DriveReference
import id.homebase.api.youauth.SecurityContext
import id.homebase.api.youauth.getUniqueDrivesWithHighestPermission
import kotlin.uuid.Uuid

/**
 * Whether the current user may emoji-react to and/or comment on a post — the Kotlin form of the
 * dotyoucore-js `useCanReact` result union
 * (`{ canReact: 'emoji' | 'comment' | true } | { canReact: false, details }`).
 */
sealed interface CanReact {

    /** Both emoji reactions and comments are allowed (web `canReact: true`). */
    data object All : CanReact

    /** Emoji reactions only — the channel drive carries React but not Comment (web `'emoji'`). */
    data object EmojiOnly : CanReact

    /** Comments only — the channel drive carries Comment but not React (web `'comment'`). */
    data object CommentOnly : CanReact

    /** Neither is allowed; [reason] mirrors the web's `details` string. */
    data class Denied(val reason: DenyReason) : CanReact

    val allowsEmoji: Boolean get() = this is All || this is EmojiOnly

    val allowsComment: Boolean get() = this is All || this is CommentOnly
}

/** Why reacting is denied — mirrors dotyoucore-js `useCanReact`'s `details` values. */
enum class DenyReason {
    /** Web `NOT_AUTHENTICATED`: no session at all. */
    NotAuthenticated,

    /** Web `NOT_AUTHORIZED`: authenticated, but the channel drive grants neither React nor Comment. */
    NotAuthorized,

    /** Web `DISABLED_ON_POST`: the author switched interaction off (`reactAccess: false`). */
    DisabledOnPost,

    /** Web `UNKNOWN`: the security context could not be resolved, so permissions are unknown. */
    Unknown,
}

/**
 * Verbatim port of dotyoucore-js `useCanReact`
 * (`packages/common/common-app/src/hooks/reactions/useCanReact.tsx`).
 *
 * Pure by design: [securityContext] is whatever context governs the post — the **local** one for
 * the user's own posts, the **peer's** one for a followed identity's post, since the grant that
 * decides whether you may comment on someone else's channel is the grant *they* issued you. See
 * [FeedPermissionService] for how that context is sourced.
 *
 * Only grants naming *both* [channelDriveAlias] and [channelDriveType] count; a grant on any other
 * drive is irrelevant to this channel. Grants for the same drive coming from several permission
 * groups are merged with [getUniqueDrivesWithHighestPermission] before React/Comment are read.
 *
 * One deliberate deviation from the web: a null [securityContext] yields [DenyReason.Unknown]
 * rather than [DenyReason.NotAuthorized]. In JS an unresolved context optional-chains to an empty
 * grant list and so is indistinguishable from "explicitly granted nothing"; separating them lets
 * the caller retry a failed fetch instead of showing a hard denial.
 */
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

    // Only the explicit "off" disables a post; EmojiOnly/CommentOnly are rendering hints the web
    // applies further down, not denials here.
    if (reactAccess == ReactAccess.None) return CanReact.Denied(DenyReason.DisabledOnPost)

    if (!hasReact) return CanReact.CommentOnly
    if (!hasComment) return CanReact.EmojiOnly
    return CanReact.All
}

/**
 * Drive ids arrive from the API as strings in either GUID spelling; compare them parsed so a
 * hyphen-less alias still matches. An unparseable id never matches.
 */
private fun DriveReference.matches(alias: Uuid, type: Uuid): Boolean =
    alias == this.alias.toUuidOrNull() && type == this.type.toUuidOrNull()

internal fun String.toUuidOrNull(): Uuid? = runCatching { Uuid.parse(this) }.getOrNull()
