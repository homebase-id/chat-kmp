package id.homebase.api.client.peer

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

@Serializable
private data class PeerTokenRequest(val identity: OdinId)

/**
 * Auth material for connecting a websocket directly to a peer's (owner's) host. `sharedSecret` is
 * base64 — the bytes are used to encrypt/decrypt the peer websocket frames.
 */
@Serializable
data class PeerTokenResponse(
    val authenticationToken64: String,
    val sharedSecret: String,
)

@Serializable
private data class PeerPushSubscriptionRequest(
    val identity: OdinId,
    val subscriptionId: Uuid,
)

/**
 * Talks to the user's OWN host for the two peer-notification primitives that the live community
 * websocket needs. Mirrors the JS `getOrFetchPeerToken` (`WebsocketProviderOverPeer.ts`) and
 * `SubscribeToPeerNotifications` (`community-app/providers/PeerNotificationSubscriber.ts`).
 */
class PeerNotificationProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /**
     * Exchange (via the user's own host) for a short-lived auth token + shared secret that authorise
     * a direct websocket to [ownerOdinId]'s host. The result should be cached per owner by the
     * caller and re-fetched on `authenticationError`.
     */
    suspend fun getPeerToken(ownerOdinId: OdinId): PeerTokenResponse? {
        val creds = requireCreds()
        val url = apiUrl(creds.domain, "/peer/notify/token")
        val body = OdinSystemSerializer.serialize(PeerTokenRequest(ownerOdinId))
        val response = encryptedPostJson(
            url = url,
            token = creds.accessToken,
            jsonBody = body,
            secret = creds.secret,
        )
        if (response.status !in 200..299) {
            Logger.w(tag = TAG) {
                "getPeerToken: FAIL owner=${ownerOdinId.domainName} status=${response.status}"
            }
            return null
        }
        return deserialize(response.body)
    }

    /**
     * Arm a closed-app push subscription on the user's own host so the owner's host pushes a
     * notification when the community ([subscriptionId] = communityId) changes. Returns true on 2xx.
     */
    suspend fun subscribeToPeerNotifications(ownerOdinId: OdinId, subscriptionId: Uuid): Boolean {
        val creds = requireCreds()
        val url = apiUrl(creds.domain, "/peer/notify/subscriptions/push-notification")
        val body = OdinSystemSerializer.serialize(
            PeerPushSubscriptionRequest(identity = ownerOdinId, subscriptionId = subscriptionId)
        )
        val response = encryptedPostJson(
            url = url,
            token = creds.accessToken,
            jsonBody = body,
            secret = creds.secret,
        )
        val ok = response.status in 200..299
        if (!ok) {
            Logger.w(tag = TAG) {
                "subscribeToPeerNotifications: FAIL owner=${ownerOdinId.domainName} sub=$subscriptionId status=${response.status}"
            }
        }
        return ok
    }

    companion object {
        private const val TAG = "PeerNotify"
    }
}
