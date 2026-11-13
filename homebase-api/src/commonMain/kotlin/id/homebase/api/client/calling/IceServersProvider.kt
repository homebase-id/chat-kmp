package id.homebase.api.client.calling

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable

/** One entry of WebRTC's `iceServers`, passed to the engine as given. */
@Serializable
data class CallIceServer(val urls: List<String>, val username: String? = null, val credential: String? = null) {
    override fun toString() = "CallIceServer(urls=$urls${if (username != null) ", with login" else ""})"
}

/** [expiresAt] is when the TURN login stops opening new relays; null when there is none (no TURN on the host, or over its limit). */
@Serializable
data class IceServersResponse(val iceServers: List<CallIceServer> = emptyList(), val expiresAt: UnixTimeUtc? = null)

@Serializable
private data class IceServersRequest(val peer: String)

/** The device's own server's STUN and a short-lived TURN login for one call with [peer]; asked again before an ICE restart. */
class IceServersProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    suspend fun iceServers(peer: String): IceServersResponse {
        val creds = requireCreds()
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "/calling/ice-servers"),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(IceServersRequest(peer)),
            secret = creds.secret,
        )
        throwForFailure(response)
        return deserialize(response.body)
    }
}
