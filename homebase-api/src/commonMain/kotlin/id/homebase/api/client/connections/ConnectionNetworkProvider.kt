package id.homebase.api.client.connections

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
     * Lists the owner's circle definitions. GET /circles/definitions/list. Requires the
     * app token to hold circle-read permission; returns [] for identities with none.
     */
    suspend fun getCircleDefinitions(includeSystemCircle: Boolean = true): List<CircleDefinition> {
        val creds = requireCreds()

        val response = encryptedGet(
            url = apiUrl(creds.domain, "/circles/definitions/list"),
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
        post(endpoint, OdinIdRequest(odinId))
    }

    private suspend fun post(endpoint: String, body: Any) {
        val creds = requireCreds()

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(body),
            secret = creds.secret
        )

        throwForFailure(response)
    }

    private suspend inline fun <reified T> postAndDeserialize(
        endpoint: String,
        body: Any
    ): T {
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