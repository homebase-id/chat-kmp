package id.homebase.api.client.liverelay

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable

/**
 * HOP 1 of Live Relay: app → its own server. POSTs an opaque [blob] to the relay endpoint, which
 * fans it out (ephemeral, fire-and-forget) to each connected [recipients] identity's server. The
 * server infers the AppId from the app token — we never send it. Unreachable/non-connected
 * recipients are silently dropped server-side; a 2xx just means the relay accepted the request.
 */
class LiveRelayProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /**
     * Relay [blob] to [recipients] (domain strings) on [channelKey]. Returns true on 2xx.
     * Shared-secret-encrypted like every other V2 JSON POST.
     */
    suspend fun relay(channelKey: String, recipients: List<String>, blob: String): Boolean {
        val creds = requireCreds()
        val url = apiUrl(creds.domain, "/live-relay")
        val body = OdinSystemSerializer.serialize(
            LiveRelayRequest(channelKey = channelKey, recipients = recipients, blob = blob)
        )
        val response = encryptedPostJson(
            url = url,
            token = creds.accessToken,
            jsonBody = body,
            secret = creds.secret,
        )
        val ok = response.status in 200..299
        Logger.i(tag = TAG) {
            "SEND ch=$channelKey n=${recipients.size} bytes=${blob.length} -> ${response.status}"
        }
        return ok
    }

    companion object {
        private const val TAG = "LiveRelay"
    }
}

@Serializable
data class LiveRelayRequest(
    val channelKey: String,
    val recipients: List<String>,
    val blob: String,
)
