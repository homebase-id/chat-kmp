@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.profile

import id.homebase.api.client.ApiResponse
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Client for the Homebase **V2 Profile attributes** controller (`/api/v2/profile/attributes`).
 *
 * This is a **write-only** controller (create/edit/delete a single standard-profile attribute). To
 * READ the owner's current attribute values — needed to prefill an editor and to obtain each
 * attribute's `id` + `versionTag` — query the ProfileDrive directly (`fileType = 77`) via
 * [id.homebase.api.client.drives.query.DriveQueryProvider.queryBatch]; that read path lives in
 * [ProfileRepository], mirroring how [id.homebase.api.client.contacts.ContactsProvider] pairs with
 * [id.homebase.api.client.contacts.ContactRepository].
 *
 * Every call uses the active owner/app session and requires the app token to hold the
 * `ManageProfile` permission ([id.homebase.api.youauth.AppPermissionType.ManageProfile]); the server
 * returns 403 otherwise. Requests and responses ride the standard shared-secret-encrypted transport
 * provided by [OdinApiProviderBase].
 *
 * The write REPLACES the attribute's whole `data` object (it does not merge), so callers must send
 * the complete value every time. A 409 means the supplied `expectedVersionTag` is stale — unlike the
 * contacts endpoint the server does not auto-merge, so recovery re-reads the attribute and resends
 * (see [ProfileRepository.save]).
 */
class ProfileProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        private const val BASE = "/profile/attributes"

        /** The ProfileDrive fileType that standard-profile attribute files use. */
        const val PROFILE_ATTRIBUTE_FILE_TYPE: Int = 77
    }

    /**
     * PUT /api/v2/profile/attributes — creates or edits one attribute.
     *
     * Pass [id] = null to CREATE; pass [id] + [expectedVersionTag] to EDIT. [data] is sent whole and
     * replaces the stored value. Returns [ProfileWriteResult.Ok] (200, with the new id + versionTag)
     * or [ProfileWriteResult.Conflict] (409, stale tag). 403/5xx throw via [throwForFailure].
     */
    suspend fun saveAttribute(
        type: String,
        id: Uuid?,
        expectedVersionTag: Uuid?,
        visibility: ProfileVisibility,
        data: JsonObject,
    ): ProfileWriteResult {
        val creds = requireCreds()

        val response = encryptedPutJson(
            url = apiUrl(creds.domain, BASE),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(
                SaveProfileAttributeRequest(
                    type = type,
                    id = id,
                    expectedVersionTag = expectedVersionTag,
                    visibility = visibility.wireValue,
                    data = data,
                )
            ),
            secret = creds.secret,
        )

        return toWriteResult(response)
    }

    /**
     * DELETE /api/v2/profile/attributes/{id}?versionTag=… — removes an attribute. Returns `true` on
     * 2xx, `false` if there is no such attribute (404). Other failures throw.
     */
    suspend fun deleteAttribute(id: Uuid, versionTag: Uuid): Boolean {
        val creds = requireCreds()

        val response = encryptedDelete(
            url = apiUrl(creds.domain, "$BASE/$id?versionTag=$versionTag"),
            token = creds.accessToken,
            secret = creds.secret,
        )

        if (response.status == 404) return false
        throwForFailure(response)
        return true
    }

    private fun toWriteResult(response: ApiResponse): ProfileWriteResult =
        when {
            response.status in 200..299 ->
                ProfileWriteResult.Ok(deserialize(response.body))

            response.status == 409 ->
                ProfileWriteResult.Conflict

            else -> {
                throwForFailure(response) // always throws for non-2xx
                error("unreachable: throwForFailure did not throw for status ${response.status}")
            }
        }
}
