package id.homebase.api.client.connections

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.query.PagedResult
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlin.io.encoding.ExperimentalEncodingApi


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

@OptIn(ExperimentalEncodingApi::class)
class ConnectionRequestProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager
) : OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        private const val TAG = "ConnectionRequestProvider"
    }

    suspend fun getIncomingRequests(
        pageNumber: Int,
        pageSize: Int
    ): PagedResult<IncomingConnectionRequestResponse> {

        val creds = requireCreds()

        val endpoint = "/connections/requests"

        val response = encryptedGet(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = "type=incoming&pageNumber=$pageNumber&pageSize=$pageSize"
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    suspend fun getOutgoingRequests(
        pageNumber: Int,
        pageSize: Int
    ): PagedResult<OutgoingConnectionRequestResponse> {

        val creds = requireCreds()

        val endpoint = "/connections/requests"

        val response = encryptedGet(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            secret = creds.secret,
            queryString = "type=outgoing&pageNumber=$pageNumber&pageSize=$pageSize"
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    // ------------------------------------------------------------
    // GET INCOMING
    // ------------------------------------------------------------

    suspend fun getIncomingRequest(
        senderId: OdinId
    ): IncomingConnectionRequestResponse {

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
    ): OutgoingConnectionRequestResponse {

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
    // AUTO-CONNECT
    // ------------------------------------------------------------

    /**
     * Sends a connection request on the app-origin auto-connect path. The server runs the
     * recipient's auto-accept synchronously, so a single HTTP call can resolve the whole flow
     * and return a typed [ConnectionRequestResult]. Non-2xx responses throw — callers treat transport
     * / auth failures separately from in-band [AutoConnectOutcome]s.
     */
    suspend fun autoConnect(
        request: ConnectionRequestHeader
    ): ConnectionRequestResult {

        val creds = requireCreds()

        val endpoint = "/connections/requests/auto-connect"

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(request),
            secret = creds.secret
        )

        throwForFailure(response)
        return deserialize(response.body)
    }

    // ------------------------------------------------------------
    // ACCEPT (PUT)
    // ------------------------------------------------------------

    suspend fun acceptIncomingRequest(
        senderId: OdinId,
        request: AcceptConnectionRequestV2
    ) {

        val creds = requireCreds()

        val endpoint =
            "/connections/requests/incoming/$senderId"

        val response = encryptedPutJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(request),
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

    /**
     * `notifyRemote=true` (plain, unencrypted query param — not the `ss` encrypted-queryString
     * mechanism [encryptedGet] uses) asks the server to also transit a best-effort, eventual
     * withdrawal to the recipient's server, deleting their matching pending incoming request.
     * Always on: idempotent, safe to send unconditionally for a normal user-initiated cancel
     * (server-side default stays off for other unrelated callers of the same endpoint).
     */
    suspend fun cancelOutgoingRequest(
        recipientId: OdinId
    ) {

        val creds = requireCreds()

        val endpoint =
            "/connections/requests/outgoing/$recipientId?notifyRemote=true"

        val response = encryptedDelete(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            secret = creds.secret
        )

        throwForFailure(response)
    }
}
