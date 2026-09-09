package id.homebase.api.client.notifications

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PushSubscriptionRequest(
    @SerialName("DeviceToken") val deviceToken: String,
    @SerialName("DevicePlatform") val devicePlatform: String,
    @SerialName("FriendlyName") val friendlyName: String
)

/**
 * Raw-VAPID (browser) push subscription. The server routes on an empty `FirebaseDeviceToken`, so
 * a browser subscription posted through the firebase endpoint is accepted and then never
 * delivered to — the shape and the endpoint both have to differ from [PushSubscriptionRequest].
 */
@Serializable
data class WebPushSubscriptionRequest(
    val friendlyName: String,
    val endpoint: String,
    val expirationTime: Long? = null,
    val auth: String,
    val p256DH: String,
)

@Serializable
data class PushSubscriptionResponse(
    val accessRegistrationId: String,
    val friendlyName: String,
    val expirationTime: Long,
    val subscriptionStartedDate: Long,
    val firebaseDeviceToken: String? = null
)

class PushNotificationApi(httpClient: HttpClient, credentialsManager: CredentialsManager) :
    OdinApiProviderBase(httpClient, credentialsManager) {

    suspend fun subscribe(deviceToken: String, devicePlatform: String, friendlyName: String) {
        val creds = requireCreds()
        val requestBody =
            PushSubscriptionRequest(
                deviceToken = deviceToken,
                devicePlatform = devicePlatform,
                friendlyName = friendlyName
            )

        val jsonBody = OdinSystemSerializer.serialize(requestBody)

        val response =
            encryptedPostJson(
                url = apiUrl(creds.domain, "/notify/push/subscribe-firebase"),
                token = creds.accessToken,
                jsonBody = jsonBody,
                secret = creds.secret
            )

        throwForFailure(response)
    }

    suspend fun subscribeWebPush(request: WebPushSubscriptionRequest) {
        val creds = requireCreds()

        val response =
            encryptedPostJson(
                url = apiUrl(creds.domain, "/notify/push/subscribe"),
                token = creds.accessToken,
                jsonBody = OdinSystemSerializer.serialize(request),
                secret = creds.secret
            )

        throwForFailure(response)
    }

    /**
     * The tenant's VAPID application server key. A guest endpoint outside /api/v2 — unauthenticated,
     * unencrypted, and it answers with a bare base64url string rather than JSON — so neither
     * [encryptedGet] nor [deserialize] applies. Generated once at identity init and never rotated.
     */
    suspend fun getVapidPublicKey(): String {
        val creds = requireCreds()
        val body = httpClient
            .get("https://${creds.domain}/api/guest/v1/public/keys/notifications_pk")
            .bodyAsText()
        return body.trim().trim('"')
    }

    suspend fun getSubscription(): PushSubscriptionResponse? {
        val creds = requireCreds()
        val response = encryptedGet(
            url = apiUrl(creds.domain, "/notify/push/subscription"),
            token = creds.accessToken,
            secret = creds.secret
        )
        if (response.status == 404) return null
        throwForFailure(response)
        return deserialize(response.body)
    }

    suspend fun unsubscribe() {
        val creds = requireCreds()

        val response =
            encryptedPostJson(
                url = apiUrl(creds.domain, "/notify/push/unsubscribe"),
                token = creds.accessToken,
                jsonBody = "{}",
                secret = creds.secret,
            )
        throwForFailure(response)
    }
}
