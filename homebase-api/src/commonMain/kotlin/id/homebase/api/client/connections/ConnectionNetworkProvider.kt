package id.homebase.api.client.connections

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiProviderBase
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

    suspend fun confirmConnection(odinId: OdinId) {
        postOdinId("/connections/confirm-connection", odinId)
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