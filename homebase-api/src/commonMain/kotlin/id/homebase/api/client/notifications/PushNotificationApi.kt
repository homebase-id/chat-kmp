package id.homebase.api.client.notifications

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
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
