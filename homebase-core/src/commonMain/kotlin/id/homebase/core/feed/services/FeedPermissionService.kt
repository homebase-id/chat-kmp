package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.youauth.SecurityContext
import id.homebase.api.youauth.SecurityContextProvider
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Which security context governs a post depends on who wrote it: the user's own post uses the local context,
// a followed identity's post needs *that peer's* context, because the grant deciding whether you may comment
// on their channel is the one THEY issued you. Never throws: any failure degrades to DenyReason.Unknown,
// which the UI can retry rather than treat as a hard denial.
class FeedPermissionService(
    private val securityContextProvider: SecurityContextProvider,
    private val credentialsManager: CredentialsManager,
) {

    companion object {
        private const val TAG = "FeedPermissionService"
    }

    // Immutable map, always replaced — @Volatile makes the lock-free hit path safe; fetchLock only serialises
    // misses so a screenful of posts can't stampede the endpoint. Keyed by identity: the context is identity-scoped.
    @Volatile
    private var contexts: Map<OdinId, SecurityContext> = emptyMap()
    private val fetchLock = Mutex()

    suspend fun canReact(post: FeedPostItem): CanReact = try {
        val self = credentialsManager.getActiveCredentials()?.domain
            ?: return CanReact.Denied(DenyReason.NotAuthenticated)

        // A file carrying neither author nor sender is an own-drive write; reading it as self is what an
        // absent sender means.
        val author = post.authorOdinId ?: self
        val channelDriveAlias =
            post.channelId.toUuidOrNull() ?: FeedProtocol.PublicChannelDriveAlias

        val context = contextFor(author, self)

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

    fun reset() {
        contexts = emptyMap()
    }

    private suspend fun contextFor(identity: OdinId, self: OdinId): SecurityContext? {
        contexts[identity]?.let { return it }
        // A failed fetch is deliberately not cached — it is transient, and caching it would pin
        // DenyReason.Unknown for the rest of the session.
        return fetchLock.withLock {
            contexts[identity]
                ?: (if (identity == self) securityContextProvider.getSecurityContext()
                else securityContextProvider.getPeerSecurityContext(identity))
                    ?.also { contexts = contexts + (identity to it) }
        }
    }
}
