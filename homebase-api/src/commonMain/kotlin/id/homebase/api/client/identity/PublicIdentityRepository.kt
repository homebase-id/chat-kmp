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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "PublicIdentityRepository"

/**
 * Fetches public identity info (display name, first/last name, status) for any OdinId by
 * reading `https://<odinId>/cdn/sitedata.json`.
 *
 * Also exposes lower-level helpers ([fetchSiteData], [siteDataSectionHeader],
 * [siteDataSectionData]) that other components can reuse when they need to extract
 * additional sections from the same document — `OwnerSessionRepository` uses this to
 * also pull the `photo` section for the signed-in user.
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

    /**
     * Fetches and parses the raw `sitedata.json` root array for an identity, returning `null`
     * on any fetch/parse failure. Shared entry point for callers that need sections beyond
     * the core public-identity fields.
     */
    suspend fun fetchSiteData(odinId: OdinId): JsonArray? {
        val url = "https://$odinId/cdn/sitedata.json"

        val response = try {
            httpClient.get(url)
        } catch (e: Exception) {
            Logger.e(tag = TAG) { "Fetching $url failed: ${e.message}" }
            return null
        }

        if (!response.status.isSuccess()) return null

        return try {
            Json.parseToJsonElement(response.bodyAsText()).jsonArray
        } catch (e: Exception) {
            Logger.e(tag = TAG) { "Parsing $url failed: ${e.message}" }
            null
        }
    }

    private suspend fun fetch(odinId: OdinId): PublicIdentity? {
        val root = fetchSiteData(odinId) ?: return null

        return try {
            val nameData = root.siteDataSectionData("name")
            val statusData = root.siteDataSectionData("status")
            // Bio summary lives in its own section; its text field key is `short_bio`
            // (ProfileConfig.BioId in odin-js), same key the long bio uses.
            val bioSummaryData = root.siteDataSectionData("short-bio-summary")

            PublicIdentity(
                odinId = odinId,
                displayName = nameData?.get("displayName")?.jsonPrimitive?.contentOrNull,
                firstName = nameData?.get("givenName")?.jsonPrimitive?.contentOrNull,
                surName = nameData?.get("surname")?.jsonPrimitive?.contentOrNull,
                status = statusData?.get("status")?.jsonPrimitive?.contentOrNull,
                shortBioSummary = bioSummaryData?.get("short_bio")?.jsonPrimitive?.contentOrNull,
            )
        } catch (e: Exception) {
            Logger.e(tag = TAG) { "Parsing identity sections for $odinId failed: ${e.message}" }
            null
        }
    }
}

/**
 * Returns the `files[0].header` JSON object for the named section (e.g. "name", "photo",
 * "status") of a `sitedata.json` root array, or `null` if the section isn't present.
 */
fun JsonArray.siteDataSectionHeader(sectionName: String): JsonObject? {
    val section = find {
        it.jsonObject["name"]?.jsonPrimitive?.content == sectionName
    }?.jsonObject ?: return null

    return section["files"]
        ?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("header")
        ?.jsonObject
}

/**
 * Returns the inner `data` JSON object parsed out of `header.fileMetadata.appData.content`
 * for the named section, or `null` if absent.
 */
fun JsonArray.siteDataSectionData(sectionName: String): JsonObject? {
    val content = siteDataSectionHeader(sectionName)
        ?.get("fileMetadata")
        ?.jsonObject?.get("appData")
        ?.jsonObject?.get("content")
        ?.jsonPrimitive?.content
        ?: return null

    return Json.parseToJsonElement(content).jsonObject["data"]?.jsonObject
}
