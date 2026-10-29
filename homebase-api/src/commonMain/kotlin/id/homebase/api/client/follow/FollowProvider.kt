package id.homebase.api.client.follow

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.OdinIdRequest
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.drives.query.CursoredResult
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable

// Who this identity follows and who follows it, over /api/v2/followers/* (odin-core #1626).
class FollowProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        private const val TAG = "FollowProvider"
        private const val BASE = "/followers"
    }

    /** The server treats "already followed" as success. [FollowRequest.synchronizeFeedHistoryNow] backfills their posts. */
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

    suspend fun unfollow(odinId: OdinId) {
        val creds = requireCreds()
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "$BASE/unfollow"),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(OdinIdRequest(odinId)),
            secret = creds.secret,
        )
        throwForFailure(response)
    }

    suspend fun fetchFollowing(cursor: String? = null, count: Int? = null): CursoredResult<List<String>> {
        val creds = requireCreds()
        val queryString = buildQuery(cursor, count)
        val response = encryptedGet(
            url = apiUrl(creds.domain, "$BASE/identities-i-follow"),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = queryString,
        )
        throwForFailure(response)
        return toCursoredList(deserialize<FollowPageResponse>(response.body))
    }

    suspend fun fetchFollowers(cursor: String? = null, count: Int? = null): CursoredResult<List<String>> {
        val creds = requireCreds()
        val queryString = buildQuery(cursor, count)
        val response = encryptedGet(
            url = apiUrl(creds.domain, "$BASE/following-me"),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = queryString,
        )
        throwForFailure(response)
        return toCursoredList(deserialize<FollowPageResponse>(response.body))
    }

    /** Whether this identity follows [odinId]. */
    suspend fun isFollowing(odinId: OdinId): Boolean = hasFollow("$BASE/identity-i-follow", odinId)

    /** Whether [odinId] follows this identity. */
    suspend fun isFollowedBy(odinId: OdinId): Boolean = hasFollow("$BASE/follower", odinId)

    // Both answer the follow definition, or 204 with no body when there is none. A 404 is a server without the
    // v2 follower routes, so it throws rather than reading as "no".
    private suspend fun hasFollow(path: String, odinId: OdinId): Boolean {
        val creds = requireCreds()
        val response = encryptedGet(
            url = apiUrl(creds.domain, path),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = "odinId=${odinId.domainName}",
        )
        throwForFailure(response)
        val def = runCatching { deserialize<FollowDefinition>(response.body) }.getOrNull()
        return def != null && def.odinId != null
    }

    /**
     * People you may know: your server asks your connections for what they share of their own connections and
     * follows (odin-core #1626). [refresh] skips the server's hour-long cache.
     */
    suspend fun fetchSuggestions(count: Int = 50, refresh: Boolean = false): FollowerSuggestions {
        val creds = requireCreds()
        val response = encryptedGet(
            url = apiUrl(creds.domain, "$BASE/suggestions"),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = "count=$count&refresh=$refresh",
        )
        throwForFailure(response)
        return deserialize<FollowerSuggestions>(response.body)
    }

    suspend fun syncFeedHistory(odinId: OdinId) {
        val creds = requireCreds()
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "$BASE/sync-feed-history"),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(OdinIdRequest(odinId)),
            secret = creds.secret,
        )
        throwForFailure(response)
    }

    private fun buildQuery(cursor: String?, count: Int?): String? = buildString {
        cursor?.let { append("cursor=$it") }
        count?.let {
            if (isNotEmpty()) append("&")
            append("count=$it")
        }
    }.ifBlank { null }

    private fun toCursoredList(page: FollowPageResponse): CursoredResult<List<String>> =
        CursoredResult(results = page.results, cursorState = page.cursor.orEmpty())
}

@Serializable
enum class FollowNotificationType {
    AllNotifications,
    SelectedChannels,
}

// When [notificationType] is SelectedChannels, [channels] lists the channel drives; otherwise it follows all
// channels and may be null. [synchronizeFeedHistoryNow] pulls their existing posts into the feed right away.
@Serializable
data class FollowRequest(
    val odinId: OdinId,
    val notificationType: FollowNotificationType,
    val channels: List<TargetDrive>? = null,
    val synchronizeFeedHistoryNow: Boolean = false,
)

// The server's CursoredResult: the next page's cursor is `cursor`.
@Serializable
data class FollowPageResponse(
    val results: List<String> = emptyList(),
    val cursor: String? = null,
)

@Serializable
data class FollowDefinition(
    val odinId: String? = null,
    val notificationType: FollowNotificationType? = null,
    val channels: List<TargetDrive>? = null,
)

@Serializable
data class FollowerSuggestion(
    val odinId: String,
    /** Your connections that list them as a connection. */
    val viaConnections: List<String> = emptyList(),
    /** Your connections that follow them. */
    val viaFollows: List<String> = emptyList(),
    val score: Int = 0,
)

// How many of your connections were asked, and how many answered (the rest refused, timed out or were offline)
@Serializable
data class FollowerSuggestions(
    val results: List<FollowerSuggestion> = emptyList(),
    val asked: Int = 0,
    val answered: Int = 0,
    val computedAt: Long = 0,
)
