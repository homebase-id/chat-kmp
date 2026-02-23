package id.homebase.api.client.connections

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.query.PagedResult
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

// ==================== MODELS ====================

@Serializable
data class ConnectionRequestHeader(
    val id: Uuid,
    val recipient: OdinId,
    val message: String? = null,
    val circleIds: List<Uuid>? = null,
    val introducerOdinId: OdinId? = null,
    val connectionRequestOrigin: String? = null
)

@Serializable
data class ConnectionRequestResponse(
    val contactData: String? = null,
    val senderOdinId: OdinId,
    val circleIds: List<Uuid>? = null,
    val message: String? = null,
    val introducerOdinId: OdinId? = null,
    val receivedTimestampMilliseconds: Long,
    val connectionRequestOrigin: String, // ConnectionRequestOrigin
    val recipient: OdinId,
    val direction: String // incoming  or outgoing
)
//
//enum class ConnectionRequestDirection {
//    Incoming,
//    Outgoing
//}

// TODO
//public enum ConnectionRequestOrigin
//{
//    None = 0,
//
//    /// <summary>
//    /// Indicates the connection request was sent by the identity owner
//    /// </summary>
//    IdentityOwner = 1,
//
//    /// <summary>
//    /// Indicates the connection request came because another identity introduce you to the recipient
//    /// </summary>
//    Introduction = 2
//}

// ==================== PROVIDER ====================

@OptIn(ExperimentalEncodingApi::class)
class ConnectionRequestProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager
) : OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        private const val TAG = "ConnectionRequestProvider"
    }

    // ------------------------------------------------------------
    // LIST (incoming | outgoing)
    // ------------------------------------------------------------

    suspend fun getRequests(
        type: String,
        pageNumber: Int,
        pageSize: Int
    ): PagedResult<ConnectionRequestResponse> {

        require(type == "incoming" || type == "outgoing") {
            "type must be incoming or outgoing"
        }

        val creds = requireCreds()

        val endpoint =
            "/connections/requests?type=$type&pageNumber=$pageNumber&pageSize=$pageSize"

        val response = encryptedGet(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            secret = creds.secret
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    // ------------------------------------------------------------
    // GET INCOMING
    // ------------------------------------------------------------

    suspend fun getIncomingRequest(
        senderId: OdinId
    ): ConnectionRequestResponse {

        val creds = requireCreds()

        val endpoint =
            "/connections/requests/incoming/$senderId"

        val response = encryptedGet(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            secret = creds.secret
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    // ------------------------------------------------------------
    // GET OUTGOING
    // ------------------------------------------------------------

    suspend fun getOutgoingRequest(
        recipientId: OdinId
    ): ConnectionRequestResponse {

        val creds = requireCreds()

        val endpoint =
            "/connections/requests/outgoing/$recipientId"

        val response = encryptedGet(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            secret = creds.secret
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    // ------------------------------------------------------------
    // SEND
    // ------------------------------------------------------------

    suspend fun sendConnectionRequest(
        request: ConnectionRequestHeader
    ) {

        val creds = requireCreds()

        val endpoint = "/connections/requests"

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(request),
            secret = creds.secret
        )

        throwForFailure(response)
    }

    // ------------------------------------------------------------
    // ACCEPT (PUT)
    // ------------------------------------------------------------

    suspend fun acceptIncomingRequest(
        senderId: OdinId
    ) {

        val creds = requireCreds()

        val endpoint =
            "/connections/requests/incoming/$senderId"

        val response = encryptedPutJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = "{}",   // API expects no body
            secret = creds.secret
        )

        throwForFailure(response)
    }

    // ------------------------------------------------------------
    // REJECT (DELETE incoming)
    // ------------------------------------------------------------

    suspend fun rejectIncomingRequest(
        senderId: OdinId
    ) {

        val creds = requireCreds()

        val endpoint =
            "/connections/requests/incoming/$senderId"

        val response = encryptedDelete(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            secret = creds.secret
        )

        throwForFailure(response)
    }

    // ------------------------------------------------------------
    // CANCEL (DELETE outgoing)
    // ------------------------------------------------------------

    suspend fun cancelOutgoingRequest(
        recipientId: OdinId
    ) {

        val creds = requireCreds()

        val endpoint =
            "/connections/requests/outgoing/$recipientId"

        val response = encryptedDelete(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            secret = creds.secret
        )

        throwForFailure(response)
    }
}
