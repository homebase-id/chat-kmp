package id.homebase.api.client.connections

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalEncodingApi::class)
class ConnectionNetworkProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager
) : OdinApiProviderBase(httpClient, credentialsManager) {

    suspend fun block(odinId: OdinId) {
        postOdinId("/connections/block", odinId)
    }

    suspend fun unblock(odinId: OdinId) {
        postOdinId("/connections/unblock", odinId)
    }

    suspend fun disconnect(odinId: OdinId) {
        postOdinId("/connections/disconnect", odinId)
    }

    private companion object {
        const val TAG = "ConnectionNetworkProvider"
    }

    /**
     * Stamps the owner's review of [odinId] and enrols [circleIds], atomically.
     *
     * Additive and idempotent: circles the contact already holds are untouched, and re-sending one
     * is a no-op. An empty [circleIds] is a real review — the "chat only" outcome — so it is sent
     * as `[]`, never omitted.
     *
     * Accepting an incoming request already stamps the review server-side; this is for the New
     * pile (introduction auto-accepts) and for enrolling more circles later.
     *
     * @throws id.homebase.api.client.OdinClientException [OdinClientErrorCode.IdentityMustBeConnected]
     *   when [odinId] is not a connection.
     */
    suspend fun reviewConnection(odinId: OdinId, circleIds: List<Uuid> = emptyList()) {
        Logger.i(tag = TAG) { "POST /connections/review odinId=$odinId circles=${circleIds.size}" }
        post("/connections/review", ReviewConnectionRequest(odinId, circleIds))
    }

    /**
     * Clears the review stamp, dropping [odinId] back to New.
     *
     * Withdraws the vouching only — the contact keeps every circle and every grant they had. To
     * take capability away, revoke the circles separately.
     *
     * @throws id.homebase.api.client.OdinClientException
     *   [OdinClientErrorCode.CannotClearReviewWhilePersonalCircleMember] while the contact still
     *   holds a review-granted personal circle; the message names it. Ambient membership does not
     *   trigger this.
     */
    suspend fun clearConnectionReview(odinId: OdinId) {
        postOdinId("/connections/review/clear", odinId)
    }

    suspend fun verifyConnection(odinId: OdinId): IcrVerificationResult {
        return postAndDeserialize("/connections/verify-connection", OdinIdRequest(odinId))
    }

    suspend fun getTroubleshootingInfo(odinId: OdinId): IcrTroubleshootingInfo {
        return postAndDeserialize("/connections/troubleshooting-info", OdinIdRequest(odinId))
    }

    // ✅ GET
    suspend fun getConnectionStatus(odinId: OdinId): RedactedIdentityConnectionRegistration? {
        val creds = requireCreds()

        val response = encryptedGet(
            url = apiUrl(creds.domain, "/connections/status"),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = "odinId=$odinId"
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    // ------------------------------------------------------------
    // LISTS (GET)
    // ------------------------------------------------------------

    suspend fun getConnected(
        count: Int,
        cursor: String?
    ): CursoredResult<RedactedIdentityConnectionRegistration> {

        val creds = requireCreds()

        val qs = buildString {
            append("count=$count")
            if (!cursor.isNullOrBlank()) append("&cursor=$cursor")
        }

        val response = encryptedGet(
            url = apiUrl(creds.domain, "/connections/connected"),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = qs
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    suspend fun getBlocked(
        count: Int,
        cursor: String?
    ): CursoredResult<RedactedIdentityConnectionRegistration> {

        val creds = requireCreds()

        val qs = buildString {
            append("count=$count")
            if (!cursor.isNullOrBlank()) append("&cursor=$cursor")
        }

        val response = encryptedGet(
            url = apiUrl(creds.domain, "/connections/blocked"),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = qs
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    /**
     * Lists the owner's circles, each with its members, in one round-trip.
     * GET /api/v2/connections/circles/with-members (OwnerOrApp — guest tokens are
     * rejected with 403). [includeSystemCircle] = false drops the built-in system circle.
     */
    suspend fun getCirclesWithMembers(includeSystemCircle: Boolean = true): List<CircleWithMembers> {
        val creds = requireCreds()

        val response = encryptedGet(
            url = apiUrl(creds.domain, "/connections/circles/with-members"),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = "includeSystemCircle=$includeSystemCircle",
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    /**
     * Identities with a sealed deposit on [circleId] that has not landed yet.
     *
     * Scans connections server-side, so it is for one circle on demand — never a loop over a
     * circle list. [getCirclesWithMembers] already returns `pendingMembers` for every circle in
     * one round-trip; use that when showing more than one.
     */
    /**
     * Finish circle enrollments queued for this app — the HTTP equivalent of the socket's
     * `processEnrollments`, for when there is no live connection to send it on.
     *
     * Idempotent, and a no-op without the permission.
     */
    suspend fun processEnrollments(): ProcessEnrollmentsResult =
        postAndDeserialize("/connections/enrollments/process", EmptyRequest())

    /**
     * Connections that qualify for one of [appId]'s circles but are not in it yet.
     *
     * An app may ask only about itself — the list is computed across every connection on the
     * identity, so asking about another app would read more than the caller owns. Circles with
     * nothing to offer are omitted, so an empty list means there is nothing to show.
     *
     * Requires the ReadConnections permission key.
     */
    suspend fun getEnrollmentCandidates(appId: String): List<CircleEnrollmentCandidates> {
        val creds = requireCreds()

        val response = encryptedGet(
            url = apiUrl(creds.domain, "/connections/circles/enrollment-candidates"),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = "appId=$appId",
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    /**
     * Add several identities to one circle at once.
     *
     * Reports per identity rather than failing the batch. A circle granting Read yields
     * **deposits**, not memberships: completing one escrows each drive's storage key for the
     * member, which needs the connection's peer key, and an app cannot reach it. That is success —
     * the grant is sealed and becomes real the next time that connection's key is in scope.
     *
     * An app may only enrol into a circle it owns; an owner circle is refused outright.
     */
    suspend fun addManyToCircle(circleId: String, odinIds: List<String>): EnrollmentResult =
        postAndDeserialize(
            "/connections/circles/add-many",
            AddManyCircleMembershipRequest(circleId, odinIds),
        )

    suspend fun getPendingCircleMembers(circleId: Uuid): List<PendingCircleMember> {
        val creds = requireCreds()

        val response = encryptedGet(
            url = apiUrl(creds.domain, "/connections/circles/pending"),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = "circleId=$circleId",
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    suspend fun getCircleMembers(circleId: Uuid): List<OdinId> {
        val creds = requireCreds()

        val response = encryptedGet(
            url = apiUrl(creds.domain, "/connections/circles"),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = "circleId=$circleId"
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    suspend fun addToCircle(circleId: Uuid, odinId: OdinId) {
        post(
            "/connections/circles/add",
            AddCircleMembershipRequest(odinId, circleId)
        )
    }

    suspend fun removeFromCircle(circleId: Uuid, odinId: OdinId) {
        post(
            "/connections/circles/revoke",
            RevokeCircleMembershipRequest(odinId, circleId)
        )
    }

    private suspend fun postOdinId(endpoint: String, odinId: OdinId) {
        Logger.i(tag = TAG) { "POST $endpoint odinId=$odinId" }
        post(endpoint, OdinIdRequest(odinId))
    }

    /**
     * Serialize [body] with its concrete (reified) type, then POST. Serializing through a
     * `body: Any` parameter erases the type and makes kotlinx.serialization look for an
     * `Any` serializer (which doesn't exist) — so the reified type must be captured here.
     */
    private suspend inline fun <reified T> post(endpoint: String, body: T) {
        postJson(endpoint, OdinSystemSerializer.serialize(body))
    }

    /** Shared transport + logging. Takes an already-serialized [jsonBody]. */
    private suspend fun postJson(endpoint: String, jsonBody: String) {
        val creds = requireCreds()
        val url = apiUrl(creds.domain, endpoint)

        val response = try {
            encryptedPostJson(
                url = url,
                token = creds.accessToken,
                jsonBody = jsonBody,
                secret = creds.secret
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "POST $endpoint threw before a response (${e::class.simpleName})" }
            throw e
        }

        Logger.i(tag = TAG) {
            "POST $endpoint -> status=${response.status} body=${response.body.take(500)}"
        }

        try {
            throwForFailure(response)
        } catch (e: Exception) {
            Logger.w(e, TAG) {
                "POST $endpoint FAILED status=${response.status} (${e::class.simpleName})"
            }
            throw e
        }
    }

    private suspend inline fun <reified Req, reified Res> postAndDeserialize(
        endpoint: String,
        body: Req
    ): Res {
        val creds = requireCreds()

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(body),
            secret = creds.secret
        )

        throwForFailure(response)
        return deserialize(response.body)
    }
}