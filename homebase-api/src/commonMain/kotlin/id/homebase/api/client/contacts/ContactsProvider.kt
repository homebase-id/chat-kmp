@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import id.homebase.api.client.ApiResponse
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Client for the Homebase **V2 Contacts** controller (`/api/v2/contacts`).
 *
 * This is a **write-only** controller. To read/list contacts, query the ContactDrive directly via
 * the drive QueryBatch API with `fileType = 100` (see
 * [id.homebase.api.client.drives.query.DriveQueryProvider.queryBatch]) — there is intentionally no
 * read/list method here.
 *
 * Every call uses the active owner/app session; the app token must hold the `ManageContacts`
 * permission (the server returns 403 otherwise). Requests and responses ride the standard
 * shared-secret-encrypted transport provided by [OdinApiProviderBase].
 *
 * Writes return a typed [ContactWriteResult] (Ok / Conflict / NotFound) rather than throwing on
 * 409/404, so callers can run the bounded merge-and-retry flow in [saveContact].
 */
class ContactsProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        private const val TAG = "ContactsProvider"
        private const val BASE = "/contacts"
        const val CONTACT_FILE_TYPE: Int = 100
    }

    // ------------------------------------------------------------
    // CREATE
    // ------------------------------------------------------------

    /**
     * POST /api/v2/contacts — creates a contact. Returns [ContactWriteResult.Ok] on 200, or
     * [ContactWriteResult.Conflict] on 409 (a contact with this id already exists — recover by
     * switching to UPDATE; see [saveContact]).
     */
    suspend fun createContact(content: ContactContent): ContactWriteResult {
        val creds = requireCreds()

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, BASE),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(CreateContactRequest(content)),
            secret = creds.secret,
        )

        return toWriteResult(response, allowNotFound = false)
    }

    // ------------------------------------------------------------
    // UPDATE
    // ------------------------------------------------------------

    /**
     * PUT /api/v2/contacts/{uniqueId} — merges [content] over the stored contact. Null/empty fields
     * are left untouched server-side. [versionTag] must be the latest known tag. Returns
     * [ContactWriteResult.Ok] on 200, [ContactWriteResult.NotFound] on 404, or
     * [ContactWriteResult.Conflict] on 409 (stale tag — retry with `conflict.versionTag`).
     */
    suspend fun updateContact(
        uniqueId: Uuid,
        content: ContactContent,
        versionTag: Uuid,
    ): ContactWriteResult {
        val creds = requireCreds()

        val response = encryptedPutJson(
            url = apiUrl(creds.domain, "$BASE/$uniqueId"),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(UpdateContactRequest(content, versionTag)),
            secret = creds.secret,
        )

        return toWriteResult(response, allowNotFound = true)
    }

    // ------------------------------------------------------------
    // DELETE
    // ------------------------------------------------------------

    /**
     * DELETE /api/v2/contacts/{uniqueId} — soft-deletes a contact. Returns `true` on 204 success,
     * `false` if there is no such active contact (404). Other failures throw.
     */
    suspend fun deleteContact(uniqueId: Uuid): Boolean {
        val creds = requireCreds()

        val response = encryptedDelete(
            url = apiUrl(creds.domain, "$BASE/$uniqueId"),
            token = creds.accessToken,
            secret = creds.secret,
        )

        if (response.status == 404) return false
        throwForFailure(response)
        return true
    }

    // ------------------------------------------------------------
    // SYNC
    // ------------------------------------------------------------

    /**
     * POST /api/v2/contacts/sync/{odinId} — ensures a contact exists for [odinId] and enriches it
     * from that identity's profile (best-effort, server-side). Always 202 Accepted; read the
     * resulting contact back from the ContactDrive. Use after connecting with someone.
     */
    suspend fun syncContact(odinId: OdinId) {
        val creds = requireCreds()

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, "$BASE/sync/$odinId"),
            token = creds.accessToken,
            jsonBody = "{}",
            secret = creds.secret,
        )

        throwForFailure(response)
    }

    // ------------------------------------------------------------
    // MERGE-AND-RETRY
    // ------------------------------------------------------------

    /**
     * Saves [content] with the required non-destructive conflict-recovery flow.
     *
     * Decide create-vs-update up front: pass [knownUniqueId] + [knownVersionTag] for a contact the
     * caller already knows (goes straight to UPDATE), or omit them for a new contact (tries CREATE
     * first, then UPDATE on 409).
     *
     * On any 409 the server has already merged field-by-field non-destructively, so recovery just
     * resends the **same** delta with the authoritative tag from `conflict.versionTag` — no drive
     * re-read, no client-side merge. The loop is bounded by [maxAttempts] to avoid livelock under
     * contention; it throws [IllegalStateException] if exhausted.
     */
    suspend fun saveContact(
        content: ContactContent,
        knownUniqueId: Uuid? = null,
        knownVersionTag: Uuid? = null,
        maxAttempts: Int = 3,
    ): ContactWriteResponse {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }

        val uniqueId: Uuid
        var versionTag: Uuid

        if (knownUniqueId != null && knownVersionTag != null) {
            // UPDATE-first: caller knows the contact.
            uniqueId = knownUniqueId
            versionTag = knownVersionTag
        } else {
            // CREATE-first; on 409 it already exists, switch to UPDATE.
            when (val created = createContact(content)) {
                is ContactWriteResult.Ok -> return created.body
                is ContactWriteResult.Conflict -> {
                    uniqueId = created.conflict.uniqueId
                    versionTag = created.conflict.versionTag
                }
                ContactWriteResult.NotFound ->
                    error("CREATE unexpectedly returned NotFound")
            }
        }

        repeat(maxAttempts) {
            when (val updated = updateContact(uniqueId, content, versionTag)) {
                is ContactWriteResult.Ok -> return updated.body
                // Someone else wrote concurrently — take the fresh tag and retry the same delta.
                is ContactWriteResult.Conflict -> versionTag = updated.conflict.versionTag
                // Rare create/delete race; surface clearly rather than looping forever.
                ContactWriteResult.NotFound ->
                    error("contact $uniqueId vanished during save (create/delete race)")
            }
        }

        error("contact write contention exceeded $maxAttempts attempts for $uniqueId")
    }

    // ------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------

    /**
     * Maps a write [response] to a [ContactWriteResult]. 409 and (when [allowNotFound]) 404 become
     * values; everything else non-2xx is delegated to [throwForFailure] (403, 5xx, …).
     */
    private fun toWriteResult(response: ApiResponse, allowNotFound: Boolean): ContactWriteResult =
        when {
            response.status in 200..299 ->
                ContactWriteResult.Ok(deserialize(response.body))

            response.status == 409 ->
                ContactWriteResult.Conflict(deserialize(response.body))

            response.status == 404 && allowNotFound ->
                ContactWriteResult.NotFound

            else -> {
                throwForFailure(response) // always throws for non-2xx
                error("unreachable: throwForFailure did not throw for status ${response.status}")
            }
        }
}
