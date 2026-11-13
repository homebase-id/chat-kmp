package id.homebase.api.client.calling

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.fromHttpToGmtDate
import kotlin.time.Clock
import kotlinx.serialization.Serializable

/** One entry of WebRTC's `iceServers`, passed to the engine as given. */
@Serializable
data class CallIceServer(val urls: List<String>, val username: String? = null, val credential: String? = null) {
    override fun toString() = "CallIceServer(urls=$urls${if (username != null) ", with login" else ""})"
}

/** odin-core's answer. [expiresAt] is when the TURN login stops opening new relays; null without one (no TURN, or over its limit). */
@Serializable
data class IceServersResponse(val iceServers: List<CallIceServer> = emptyList(), val expiresAt: UnixTimeUtc? = null)

/** [loginValidForMs] is how long the TURN login opens relays from when the answer arrived; null without one. */
class IceServerAnswer(val iceServers: List<CallIceServer>, val loginValidForMs: Long?)

@Serializable
private data class IceServersRequest(val peer: String)

/** The device's own server's STUN and a short-lived TURN login for one call with [peer]; asked again before an ICE restart. */
class IceServersProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    suspend fun iceServers(peer: String): IceServerAnswer {
        val creds = requireCreds()
        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "/calling/ice-servers"),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(IceServersRequest(peer)),
            secret = creds.secret,
        )
        throwForFailure(response)
        return deserialize<IceServersResponse>(response.body).answer(response.headers[HttpHeaders.Date], Clock.System.now().toEpochMilliseconds())
    }
}

// Read against the server's own clock (its Date header), so a device clock that is off doesn't misjudge a five-minute login.
internal fun IceServersResponse.answer(serverDate: String?, deviceNowMs: Long): IceServerAnswer {
    val serverNowMs = serverDate?.let { runCatching { it.fromHttpToGmtDate().timestamp }.getOrNull() } ?: deviceNowMs
    return IceServerAnswer(iceServers, expiresAt?.let { (it.milliseconds - serverNowMs).coerceAtLeast(0) })
}
