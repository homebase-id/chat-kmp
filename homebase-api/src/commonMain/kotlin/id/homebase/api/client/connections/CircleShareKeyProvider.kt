package id.homebase.api.client.connections

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.prototype.lib.serialization.Base64ByteArraySerializer
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
class AppKeyLockedShareKey(@Serializable(with = Base64ByteArraySerializer::class) val appKeyLockedShareKey: ByteArray? = null)

@Serializable
class SealedMemberShareKey(val odinId: String, @Serializable(with = Base64ByteArraySerializer::class) val sealedKey: ByteArray)

@Serializable
class SealedMemberShareKeys(@Serializable(with = Base64ByteArraySerializer::class) val sealedFrom: ByteArray, val members: List<SealedMemberShareKey>)

@Serializable
class MySealedShareKey(@Serializable(with = Base64ByteArraySerializer::class) val sealedKey: ByteArray? = null)

/** A circle's key, which the server stores but never opens: locked with the owning app's key, and sealed to each member. */
class CircleShareKeyProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /** Null until a device of the owning app sets it. */
    suspend fun getShareKey(circleId: Uuid): ByteArray? =
        deserialize<AppKeyLockedShareKey>(get(path(circleId))).appKeyLockedShareKey

    /** Set once: a second set is refused with CircleShareKeyAlreadySet, and that device reads the winner's. */
    suspend fun setShareKey(circleId: Uuid, appKeyLockedShareKey: ByteArray) =
        put(path(circleId), OdinSystemSerializer.serialize(AppKeyLockedShareKey(appKeyLockedShareKey)))

    suspend fun getUnsealedMembers(circleId: Uuid): List<OdinId> =
        deserialize(get("${path(circleId)}/unsealed-members"))

    /** All or none; refused with CircleShareKeyChanged when [SealedMemberShareKeys.sealedFrom] is no longer the circle's key. */
    suspend fun setMemberKeys(circleId: Uuid, keys: SealedMemberShareKeys) =
        put("${path(circleId)}/members", OdinSystemSerializer.serialize(keys))

    /** The caller's own copy of [owner]'s circle key, sealed to the caller's drive; null until [owner] seals one. */
    suspend fun getMySealedKey(owner: OdinId, circleId: Uuid): ByteArray? =
        deserialize<MySealedShareKey>(get("/peer/${owner.domainName}/circles/$circleId/share-key")).sealedKey

    private fun path(circleId: Uuid) = "/connections/circles/$circleId/share-key"

    private suspend fun get(endpoint: String): String {
        val creds = requireCreds()
        val response = encryptedGet(url = apiUrl(creds.domain, endpoint), token = creds.accessToken, secret = creds.secret)
        throwForFailure(response)
        return response.body
    }

    private suspend fun put(endpoint: String, jsonBody: String) {
        val creds = requireCreds()
        val response = encryptedPutJson(url = apiUrl(creds.domain, endpoint), token = creds.accessToken, jsonBody = jsonBody, secret = creds.secret)
        throwForFailure(response)
    }
}
