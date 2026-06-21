package id.homebase.api.client.follow

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.drives.query.CursoredResult
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable

/**
 * Client for the Homebase **followers** controller (`/api/v2/followers/...`). Ports the
 * dotyoucore-js `FollowManager`:
 *
 *  - [follow] / [unfollow] — start/stop following an identity (and, optionally, only some of their
 *    channels).
 *  - [fetchFollowing] — identities the logged-in user follows (cursored).
 *  - [fetchFollowers] — identities that follow the logged-in user (cursored).
 *  - [isFollowing] — whether the user follows a single identity.
 *  - [syncFeedHistory] — backfill a newly-followed identity's existing posts into the FeedDrive.
 *
 * Requests/responses ride the standard shared-secret-encrypted transport from
 * [OdinApiProviderBase].
 */
class FollowProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        private const val TAG = "FollowProvider"
        private const val BASE = "/followers"
    }

    /**
     * Follow [request.odinId]. The server treats an "already followed" response as success, so a
     * re-follow is idempotent. After a successful follow the caller should [syncFeedHistory] so the
     * followed identity's existing posts backfill into the FeedDrive.
     */
    suspend fun follow(request: FollowRequest) {
        val creds = requireCreds()
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "$BASE/follow"),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(request),
            secret = creds.secret,
        )
        // identityAlreadyFollowed comes back 2xx; only a real failure throws.
        throwForFailure(response)
        Logger.d(tag = TAG) { "follow: ${request.odinId.domainName} notify=${request.notificationType}" }
    }

    /** Stop following [odinId]. */
    suspend fun unfollow(odinId: OdinId) {
        val creds = requireCreds()
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "$BASE/unfollow"),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(UnfollowRequest(odinId)),
            secret = creds.secret,
        )
        throwForFailure(response)
    }

    /** The identities the logged-in user follows, paged by [cursor]. */
    suspend fun fetchFollowing(cursor: String? = null, max: Int? = null): CursoredResult<List<String>> {
        val creds = requireCreds()
        val queryString = buildQuery(cursor, max)
        val response = encryptedGet(
            url = apiUrl(creds.domain, "$BASE/IdentitiesIFollow"),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = queryString,
        )
        throwForFailure(response)
        return toCursoredList(deserialize<FollowPageResponse>(response.body))
    }

    /** The identities that follow the logged-in user, paged by [cursor]. */
    suspend fun fetchFollowers(cursor: String? = null, max: Int? = null): CursoredResult<List<String>> {
        val creds = requireCreds()
        val queryString = buildQuery(cursor, max)
        val response = encryptedGet(
            url = apiUrl(creds.domain, "$BASE/followingme"),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = queryString,
        )
        throwForFailure(response)
        return toCursoredList(deserialize<FollowPageResponse>(response.body))
    }

    /** Whether the logged-in user follows [odinId]. */
    suspend fun isFollowing(odinId: OdinId): Boolean {
        val creds = requireCreds()
        val response = encryptedGet(
            url = apiUrl(creds.domain, "$BASE/IdentityIFollow"),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = "odinId=${odinId.domainName}",
        )
        if (response.status == 404) return false
        throwForFailure(response)
        // The server returns the follow definition when following, an empty/absent body otherwise.
        val def = runCatching { deserialize<FollowDefinition>(response.body) }.getOrNull()
        return def != null && def.odinId != null
    }

    /** Backfill [odinId]'s existing posts into the FeedDrive after a fresh follow. */
    suspend fun syncFeedHistory(odinId: OdinId) {
        val creds = requireCreds()
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "$BASE/sync-feed-history"),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(SyncFeedHistoryRequest(odinId)),
            secret = creds.secret,
        )
        throwForFailure(response)
    }

    private fun buildQuery(cursor: String?, max: Int?): String? = buildString {
        cursor?.let { append("cursor=$it") }
        max?.let {
            if (isNotEmpty()) append("&")
            append("max=$it")
        }
    }.ifBlank { null }

    private fun toCursoredList(page: FollowPageResponse): CursoredResult<List<String>> =
        CursoredResult(results = page.results, cursorState = page.cursorState.orEmpty())
}

/** How a followed identity's new posts should notify the follower. Mirrors dotyoucore-js. */
@Serializable
enum class FollowNotificationType {
    AllNotifications,
    SelectedChannels,
}

/**
 * A request to follow an identity. When [notificationType] is [FollowNotificationType.SelectedChannels],
 * [channels] lists the channel drives to follow; otherwise it follows all channels and may be null.
 */
@Serializable
data class FollowRequest(
    val odinId: OdinId,
    val notificationType: FollowNotificationType,
    val channels: List<TargetDrive>? = null,
)

@Serializable
data class UnfollowRequest(val odinId: OdinId)

@Serializable
data class SyncFeedHistoryRequest(val odinId: OdinId)

/** A cursored page of identity domain names from the followers/following endpoints. */
@Serializable
data class FollowPageResponse(
    val results: List<String> = emptyList(),
    val cursorState: String? = null,
)

/** The server's stored follow definition for a single identity (returned by IdentityIFollow). */
@Serializable
data class FollowDefinition(
    val odinId: String? = null,
    val notificationType: FollowNotificationType? = null,
    val channels: List<TargetDrive>? = null,
)
