@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import id.homebase.api.client.ApiResponse
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.image.createThumbnails
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
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
 *
 * The contact JSON `content` is sent PLAINTEXT — the server encrypts it at rest so it can do
 * field-level merges. The contact IMAGE is the opposite: it is client-encrypted under the contact
 * file's AES key (see [setContactImage]); the server stores the ciphertext opaquely.
 *
 * Registered as a singleton so the per-contact AES-key cache survives across calls.
 */
@OptIn(ExperimentalEncodingApi::class)
class ContactsProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
    private val contactHeaderReader: ContactHeaderReader,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        private const val TAG = "ContactsProvider"
        private const val BASE = "/contacts"
        const val CONTACT_FILE_TYPE: Int = 100

        /** Server payload key the contact image (and its thumbnails) are stored under. */
        const val CONTACT_IMAGE_PAYLOAD_KEY: String = "prfl_pic"

        /** On-demand payload key the contact's large rich-text fields ([ContactExtData]) ride on. */
        const val CONTACT_EXT_DATA_PAYLOAD_KEY: String = "ext_data"

        /** On-demand payload key the bulk-tier per-app data ([ContactAppExtData]) rides on. */
        const val CONTACT_APP_EXT_DATA_PAYLOAD_KEY: String = "appextdata"
    }

    // Caches the contact file's AES key by uniqueId. The key is stable across content/image updates,
    // so we read the file header once and reuse it for later image writes. SecureByteArray so the
    // raw key isn't an ordinary long-lived ByteArray. Guarded by [aesKeyCacheMutex].
    private val aesKeyCache = mutableMapOf<Uuid, SecureByteArray>()
    private val aesKeyCacheMutex = Mutex()

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

    /**
     * PUT /api/v2/contacts/{uniqueId} — sets ([isEmergencyContact] = true) or clears (= false) ONLY
     * the emergency-contact flag via [EmergencyContactDelta], which always emits the bool (unlike
     * [ContactContent], whose `@EncodeDefault(NEVER)` omits a `false` and so can't express a clear).
     * Version-gated with the same bounded merge-and-retry as the image writes: on 409 it takes the
     * authoritative tag and resends. Returns [ContactWriteResult.NotFound] if there's no such contact.
     */
    suspend fun writeEmergencyContact(
        uniqueId: Uuid,
        isEmergencyContact: Boolean,
        versionTag: Uuid,
        maxAttempts: Int = 3,
    ): ContactWriteResult {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }

        return retryVersionGated(versionTag, maxAttempts) { tag ->
            val creds = requireCreds()
            val response = encryptedPutJson(
                url = apiUrl(creds.domain, "$BASE/$uniqueId"),
                token = creds.accessToken,
                jsonBody = OdinSystemSerializer.serialize(
                    SetEmergencyContactRequest(EmergencyContactDelta(isEmergencyContact), tag),
                ),
                secret = creds.secret,
            )
            toWriteResult(response, allowNotFound = true)
        }
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

        // Drop any cached AES key for this contact. The key is cached by uniqueId (= md5(odinId),
        // stable across delete+recreate), but a recreated contact is a NEW file with a NEW key — a
        // surviving entry would encrypt the recreated contact's image under the dead file's key.
        // Safe to evict even on a failed delete: the cache simply repopulates on next image use.
        aesKeyCacheMutex.withLock { aesKeyCache.remove(uniqueId) }

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
    // IMAGE (client-encrypted)
    // ------------------------------------------------------------

    /**
     * PUT /api/v2/contacts/{uniqueId}/image — sets the contact's avatar.
     *
     * Client-encrypts [imageBytes] and generated thumbnails under the contact file's AES key (read
     * from the file header on [contactDriveId], then cached), all sharing a fresh 16-byte IV, and
     * uploads the ciphertext. This is version-gated: on 409 it resends the same encrypted body with
     * the authoritative tag, bounded by [maxAttempts] (throws [IllegalStateException] if exhausted).
     * Returns [ContactWriteResult.NotFound] if there is no such contact.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun setContactImage(
        uniqueId: Uuid,
        contactDriveId: Uuid,
        imageBytes: ByteArray,
        contentType: String,
        versionTag: Uuid,
        maxAttempts: Int = 3,
    ): ContactWriteResult {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }

        val aesKey = resolveAesKey(uniqueId, contactDriveId)
            ?: return ContactWriteResult.NotFound

        // Same IV for the image and every thumbnail, per the controller's storage model.
        val iv = ByteArrayUtil.getRndByteArray(16)
        val keyHeader = KeyHeader(iv = iv, aesKey = aesKey)

        val (_, _, thumbs) = createThumbnails(imageBytes, CONTACT_IMAGE_PAYLOAD_KEY)
        val encryptedThumbnails = thumbs.map { thumb ->
            ContactImageThumbnail(
                pixelWidth = thumb.pixelWidth,
                pixelHeight = thumb.pixelHeight,
                contentType = thumb.contentType,
                content = Base64.encode(keyHeader.encryptDataAes(thumb.thumbnailBytes)),
            )
        }

        // Encrypt once; the retry loop only swaps the version tag.
        val baseRequest = SetContactImageRequest(
            versionTag = versionTag,
            contentType = contentType,
            iv = Base64.encode(iv),
            content = Base64.encode(keyHeader.encryptDataAes(imageBytes)),
            thumbnails = encryptedThumbnails,
        )

        return retryVersionGated(versionTag, maxAttempts) { tag ->
            putContactImageOnce(uniqueId, baseRequest.copy(versionTag = tag))
        }
    }

    /**
     * DELETE /api/v2/contacts/{uniqueId}/image — removes the contact's avatar. Version-gated with
     * the same bounded retry as [setContactImage].
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun deleteContactImage(
        uniqueId: Uuid,
        versionTag: Uuid,
        maxAttempts: Int = 3,
    ): ContactWriteResult {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }

        return retryVersionGated(versionTag, maxAttempts) { tag ->
            val creds = requireCreds()
            val response = encryptedDelete(
                url = apiUrl(creds.domain, "$BASE/$uniqueId/image?versionTag=$tag"),
                token = creds.accessToken,
                secret = creds.secret,
            )
            toWriteResult(response, allowNotFound = true)
        }
    }

    // ------------------------------------------------------------
    // PER-APP APP-DATA (two tiers — inline header vs bulk payload)
    // ------------------------------------------------------------
    //
    // Both tiers share the same write contract: PUT a { content, versionTag } body and DELETE with a
    // ?versionTag= query, returning the standard [ContactWriteResponse]. The appId is NOT sent — the
    // server stamps it from the auth token. [content] is plaintext (server-encrypted at rest), opaque,
    // and capped server-side (200 bytes inline / 256 KB bulk); a too-large blob comes back as 400
    // `MaxContentLengthExceeded` via [throwForFailure]'s [ClientException] — callers map that to "use
    // the bulk tier" (see [ContactRepository.setAppData]). Writes are version-gated and self-retrying
    // server-side, so the bounded [retryVersionGated] (which re-sends with the authoritative
    // `conflict.versionTag`) is all the conflict handling needed.

    /**
     * PUT /api/v2/contacts/{uniqueId}/app-data — sets this app's **inline-tier** slot (≤ 200 bytes).
     * Returns [ContactWriteResult.NotFound] on 404 (no such contact).
     */
    suspend fun setContactAppData(
        uniqueId: Uuid,
        content: String,
        versionTag: Uuid,
        maxAttempts: Int = 3,
    ): ContactWriteResult = putAppData("app-data", uniqueId, content, versionTag, maxAttempts)

    /** DELETE /api/v2/contacts/{uniqueId}/app-data — clears this app's inline-tier slot. */
    suspend fun deleteContactAppData(
        uniqueId: Uuid,
        versionTag: Uuid,
        maxAttempts: Int = 3,
    ): ContactWriteResult = deleteAppData("app-data", uniqueId, versionTag, maxAttempts)

    /**
     * PUT /api/v2/contacts/{uniqueId}/app-ext-data — sets this app's **bulk-tier** slot (≤ 256 KB),
     * stored as the [CONTACT_APP_EXT_DATA_PAYLOAD_KEY] payload. Returns [ContactWriteResult.NotFound]
     * on 404.
     */
    suspend fun setContactAppExtData(
        uniqueId: Uuid,
        content: String,
        versionTag: Uuid,
        maxAttempts: Int = 3,
    ): ContactWriteResult = putAppData("app-ext-data", uniqueId, content, versionTag, maxAttempts)

    /** DELETE /api/v2/contacts/{uniqueId}/app-ext-data — clears this app's bulk-tier slot. */
    suspend fun deleteContactAppExtData(
        uniqueId: Uuid,
        versionTag: Uuid,
        maxAttempts: Int = 3,
    ): ContactWriteResult = deleteAppData("app-ext-data", uniqueId, versionTag, maxAttempts)

    private suspend fun putAppData(
        pathSuffix: String,
        uniqueId: Uuid,
        content: String,
        versionTag: Uuid,
        maxAttempts: Int,
    ): ContactWriteResult {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        return retryVersionGated(versionTag, maxAttempts) { tag ->
            val creds = requireCreds()
            val response = encryptedPutJson(
                url = apiUrl(creds.domain, "$BASE/$uniqueId/$pathSuffix"),
                token = creds.accessToken,
                jsonBody = OdinSystemSerializer.serialize(SetContactAppDataRequest(content, tag)),
                secret = creds.secret,
            )
            toWriteResult(response, allowNotFound = true)
        }
    }

    private suspend fun deleteAppData(
        pathSuffix: String,
        uniqueId: Uuid,
        versionTag: Uuid,
        maxAttempts: Int,
    ): ContactWriteResult {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        return retryVersionGated(versionTag, maxAttempts) { tag ->
            val creds = requireCreds()
            val response = encryptedDelete(
                url = apiUrl(creds.domain, "$BASE/$uniqueId/$pathSuffix?versionTag=$tag"),
                token = creds.accessToken,
                secret = creds.secret,
            )
            toWriteResult(response, allowNotFound = true)
        }
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

    /**
     * Drops the cached contact AES keys. Called on session end / logout (from
     * [ContactRepository.reset]): keys are cached by `uniqueId` (= md5(odinId)), which collides
     * across identities, so a stale entry would encrypt a new identity's image under the previous
     * identity's key.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun clearKeyCache() {
        aesKeyCacheMutex.withLock { aesKeyCache.clear() }
    }

    // ------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------

    /**
     * Runs a version-gated write through the bounded merge-and-retry loop: on 409 it takes the
     * authoritative `conflict.versionTag` and re-invokes [call] with it. Returns on Ok/NotFound;
     * throws [IllegalStateException] if [maxAttempts] is exhausted by contention.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun retryVersionGated(
        initialTag: Uuid,
        maxAttempts: Int,
        call: suspend (Uuid) -> ContactWriteResult,
    ): ContactWriteResult {
        var tag = initialTag
        repeat(maxAttempts) {
            when (val result = call(tag)) {
                is ContactWriteResult.Ok -> return result
                ContactWriteResult.NotFound -> return ContactWriteResult.NotFound
                is ContactWriteResult.Conflict -> tag = result.conflict.versionTag
            }
        }
        error("contact image write contention exceeded $maxAttempts attempts")
    }

    /** Reads (and caches) the contact file's AES key; null if the contact header isn't found. */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun resolveAesKey(uniqueId: Uuid, contactDriveId: Uuid): SecureByteArray? {
        aesKeyCacheMutex.withLock { aesKeyCache[uniqueId] }?.let { return it }

        val header = contactHeaderReader.getHeaderByUid(contactDriveId, uniqueId) ?: return null
        val aesKey = header.keyHeader.aesKey
        aesKeyCacheMutex.withLock { aesKeyCache[uniqueId] = aesKey }
        return aesKey
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun putContactImageOnce(
        uniqueId: Uuid,
        request: SetContactImageRequest,
    ): ContactWriteResult {
        val creds = requireCreds()
        val response = encryptedPutJson(
            url = apiUrl(creds.domain, "$BASE/$uniqueId/image"),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(request),
            secret = creds.secret,
        )
        return toWriteResult(response, allowNotFound = true)
    }

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
