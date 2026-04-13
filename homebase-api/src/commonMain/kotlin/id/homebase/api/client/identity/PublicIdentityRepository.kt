package id.homebase.api.client.identity

import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fetches public identity info (display name, first/last name, status) for any OdinId by
 * reading `https://<odinId>/cdn/sitedata.json`.
 *
 * Mirrors the pattern used by `OwnerSessionRepository` for the signed-in user, but works
 * for arbitrary identities and memoizes results per OdinId for the process lifetime.
 */
class PublicIdentityRepository(
    private val httpClient: HttpClient
) {
    private val cache = mutableMapOf<OdinId, PublicIdentity>()
    private val lock = Mutex()

    /**
     * Returns the public identity if the host responded with a usable `sitedata.json`;
     * `null` if the host is unreachable, returns an error status, or the response can't be parsed.
     *
     * Use this to validate that a string points at an actual Homebase identity.
     */
    suspend fun resolve(odinId: OdinId): PublicIdentity? {
        lock.withLock { cache[odinId] }?.let { return it }

        val identity = fetch(odinId) ?: return null
        lock.withLock { cache[odinId] = identity }
        return identity
    }

    /**
     * Returns the public identity, falling back to a bare `PublicIdentity` (all nullable fields
     * null) when the host can't be reached. Prefer [resolve] when you need to distinguish
     * "resolved" from "no such identity".
     */
    suspend fun get(odinId: OdinId): PublicIdentity =
        resolve(odinId) ?: PublicIdentity(
            odinId = odinId,
            displayName = null,
            firstName = null,
            surName = null,
            status = null,
        )

    fun clear() {
        cache.clear()
    }

    private suspend fun fetch(odinId: OdinId): PublicIdentity? {
        val url = "https://$odinId/cdn/sitedata.json"

        val response = try {
            httpClient.get(url)
        } catch (e: Exception) {
            Logger.e(tag = "PublicIdentityRepository") { "Fetching $url failed: ${e.message}" }
            return null
        }

        if (!response.status.isSuccess()) return null

        return try {
            val root = Json.parseToJsonElement(response.bodyAsText()).jsonArray

            val nameSection = root.find { it.jsonObject["name"]?.jsonPrimitive?.content == "name" }
            val statusSection = root.find { it.jsonObject["name"]?.jsonPrimitive?.content == "status" }

            val nameContent = nameSection
                ?.jsonObject?.get("files")
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("header")
                ?.jsonObject?.get("fileMetadata")
                ?.jsonObject?.get("appData")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.content

            val nameData = nameContent
                ?.let { Json.parseToJsonElement(it) }
                ?.jsonObject?.get("data")
                ?.jsonObject

            val statusContent = statusSection
                ?.jsonObject?.get("files")
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("header")
                ?.jsonObject?.get("fileMetadata")
                ?.jsonObject?.get("appData")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.content

            val statusData = statusContent
                ?.let { Json.parseToJsonElement(it) }
                ?.jsonObject?.get("data")
                ?.jsonObject

            PublicIdentity(
                odinId = odinId,
                displayName = nameData?.get("displayName")?.jsonPrimitive?.contentOrNull,
                firstName = nameData?.get("givenName")?.jsonPrimitive?.contentOrNull,
                surName = nameData?.get("surname")?.jsonPrimitive?.contentOrNull,
                status = statusData?.get("status")?.jsonPrimitive?.contentOrNull,
            )
        } catch (e: Exception) {
            Logger.e(tag = "PublicIdentityRepository") { "Parsing $url failed: ${e.message}" }
            null
        }
    }
}
