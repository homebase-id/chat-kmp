package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.youauth.CallerContext
import id.homebase.api.youauth.DriveGrant
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DriveReference
import id.homebase.api.youauth.PermissionContext
import id.homebase.api.youauth.PermissionGroup
import id.homebase.api.youauth.PermissionedDrive
import id.homebase.api.youauth.SecurityContext
import id.homebase.api.youauth.SecurityContextProvider
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves [CanReact] for a feed post — the service half of the dotyoucore-js `useCanReact` port
 * (the decision itself lives in the pure [evaluateCanReact]).
 *
 * Which security context governs a post depends on **who wrote it**, exactly as in the web
 * (`SecurityProvider.ts`):
 *  - the user's own post → the local context (`GET /auth/context`), fetched once per identity and
 *    cached, so scrolling a timeline issues no repeat requests;
 *  - a followed identity's post → *that peer's* context, because the grant deciding whether you may
 *    comment on their channel is the one **they** issued you; your local context says nothing about
 *    it. See [assumedPeerContext] for why this is currently assumed rather than fetched.
 *
 * Never throws (cancellation excepted): any failure degrades to [DenyReason.Unknown], which the UI
 * can retry rather than treat as a hard denial.
 */
class FeedPermissionService(
    private val securityContextProvider: SecurityContextProvider,
    private val credentialsManager: CredentialsManager,
) {

    companion object {
        private const val TAG = "FeedPermissionService"
    }

    // Immutable map, always replaced — @Volatile makes the lock-free hit path safe; fetchLock only
    // serialises misses so a screenful of posts can't stampede the endpoint. Same shape as
    // PublicProfileProviderCached.notFoundCache. Keyed by identity, not by (identity, drive): the
    // context is identity-scoped, so one entry already serves every channel drive of that identity.
    @Volatile
    private var localContexts: Map<OdinId, SecurityContext> = emptyMap()
    private val fetchLock = Mutex()

    /** Whether the signed-in user may emoji-react to and/or comment on [post]. */
    suspend fun canReact(post: FeedPostItem): CanReact = try {
        val self = credentialsManager.getActiveCredentials()?.domain
            ?: return CanReact.Denied(DenyReason.NotAuthenticated)

        // A post whose file carries neither author nor sender is an own-drive write; reading it as
        // self is what an absent sender means, and matches the web's own-post short-circuit.
        val author = post.authorOdinId ?: self
        val channelDriveAlias =
            post.channelId.toUuidOrNull() ?: FeedProtocol.PublicChannelDriveAlias

        val context =
            if (author == self) localContext(self) else assumedPeerContext(channelDriveAlias)

        evaluateCanReact(
            securityContext = context,
            channelDriveAlias = channelDriveAlias,
            postAuthor = author,
            loggedInIdentity = self,
            reactAccess = post.reactAccess,
            isAuthenticated = true,
            isOwner = context?.caller?.securityLevel.equals("owner", ignoreCase = true),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Logger.w(throwable = t, tag = TAG) { "canReact failed for post=${post.id}: ${t.message}" }
        CanReact.Denied(DenyReason.Unknown)
    }

    /** Logout: drop the previous identity's cached context. */
    fun reset() {
        localContexts = emptyMap()
    }

    private suspend fun localContext(self: OdinId): SecurityContext? {
        localContexts[self]?.let { return it }
        // A failed fetch is deliberately not cached — it is transient (offline / 5xx) and caching
        // it would pin DenyReason.Unknown for the rest of the session.
        return fetchLock.withLock {
            localContexts[self]
                ?: securityContextProvider.getSecurityContext()
                    ?.also { localContexts = localContexts + (self to it) }
        }
    }

    /**
     * **Server gap.** The peer branch cannot be fetched: odin-core exposes the over-peer security
     * context only on v1 — `POST /api/apps/v1/transit/query/security/context`
     * (`AppPeerSecurityContextController` / `PeerSecurityContextControllerBase`), gated by
     * `[AuthorizeValidAppToken]`, which 401s for this client's UnifiedV2 bearer token. UnifiedV2
     * ships peer query, file-read, temporal, write and notification routes but no
     * `/api/v2/peer/{odinId}/security/context`; the service and perimeter halves
     * (`PeerDriveQueryService.GetRemoteDotYouContextAsync`, `PeerPerimeterSecurityController`)
     * already exist, only the v2 client-facing action is missing.
     *
     * Until that route lands, a followed identity's channel is assumed to grant React + Comment, so
     * the UI keeps offering the action instead of falsely blocking it. Everything the post itself
     * carries is still honoured — notably `reactAccess: false` still yields
     * [DenyReason.DisabledOnPost] — because this feeds the same [evaluateCanReact] as the real
     * context does. Replace this with the real fetch once the endpoint exists.
     */
    private fun assumedPeerContext(channelDriveAlias: Uuid): SecurityContext = SecurityContext(
        caller = CallerContext(securityLevel = "connected"),
        permissionContext = PermissionContext(
            permissionGroups = listOf(
                PermissionGroup(
                    driveGrants = listOf(
                        DriveGrant(
                            permissionedDrive = PermissionedDrive(
                                drive = DriveReference(
                                    alias = channelDriveAlias.toString(),
                                    type = FeedProtocol.ChannelDriveType.toString(),
                                ),
                                permission = listOf(
                                    DrivePermission.React,
                                    DrivePermission.Comment,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )
}
