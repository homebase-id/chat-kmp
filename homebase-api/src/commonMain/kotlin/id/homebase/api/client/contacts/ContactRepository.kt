@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.crypto.Md5
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import id.homebase.api.client.drives.files.PayloadDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "ContactRepository"

/**
 * Single source of truth for contacts: reads the (mandatory) Contacts drive as a live list of the
 * server-shaped [Contact] domain model, and writes through the V2 [ContactsProvider]
 * (create/update/delete/sync/image). Consumers map [Contact] into their own UI models — the
 * repository never knows about UI.
 *
 * Because one object owns both the read state and the writes, writes apply an optimistic update to
 * [contacts] directly; the authoritative row lands later via drive sync (EventBus), which
 * reconciles. Registered as a singleton.
 */
class ContactRepository(
    private val contactsProvider: ContactsProvider,
    private val contactPayloadReader: ContactPayloadReader,
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {
    private val driveId = SystemDriveConstants.contactDrive.alias

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    /** Live contacts, freshest-row-per-uniqueId, in drive order (NewestFirst). Consumers sort. */
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    // Resurrection guard: a removed contact must not reappear from a stale batch before the
    // server-confirmed delete syncs down. A StateFlow<Set> (not a plain MutableSet) because it's
    // touched from multiple threads — observeEvents runs on the shared Dispatchers.Default scope
    // while save/delete/loadAll run on arbitrary caller coroutines; atomic update {} / .value
    // avoids the data race a bare mutableSetOf would have.
    private val deletedIds = MutableStateFlow<Set<Uuid>>(emptySet())

    // Serializes loadAll so concurrent ensureLoaded() callers don't run overlapping queries.
    private val loadMutex = Mutex()

    init {
        scope.launch { observeEvents() }
    }

    // ------------------------------------------------------------
    // Lifecycle / read
    // ------------------------------------------------------------

    /** Load from the local DB. Called from the post-auth bootstrap. */
    fun start() {
        scope.launch { loadAll() }
    }

    /** Clear all in-memory state for a clean login as a different identity. */
    fun reset() {
        _contacts.value = emptyList()
        _isLoaded.value = false
        deletedIds.value = emptySet()
        // Drop the provider's per-uniqueId AES-key cache: uniqueId = md5(odinId) collides across
        // identities, so a surviving entry would encrypt the next identity's image under this
        // identity's key. Launched because clearKeyCache() is suspend (mutex-guarded).
        scope.launch { contactsProvider.clearKeyCache() }
    }

    /**
     * Guarantees the list has been loaded at least once this session, on demand — so a screen
     * never depends on the post-auth bootstrap having run. Idempotent and cheap once loaded.
     */
    suspend fun ensureLoaded() {
        if (_isLoaded.value) return
        loadMutex.withLock {
            if (_isLoaded.value) return
            loadAll()
        }
    }

    suspend fun loadAll() {
        val creds = credentialsManager.getActiveCredentials() ?: run {
            _isLoaded.value = true
            return
        }
        try {
            val result = QueryBatch(creds.getIdentityId()).queryBatchAsync(
                dbm = databaseManager,
                driveId = driveId,
                noOfItems = 1000,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(ContactsProvider.CONTACT_FILE_TYPE),
            )
            // The drive can hold >1 row per identity; NewestFirst + distinctBy keeps the freshest.
            val deleted = deletedIds.value
            val fresh = result.records
                .mapNotNull { it.toContact() }
                .distinctBy { it.uniqueId }
            _contacts.value = fresh.filter { it.uniqueId !in deleted }

            // Bound the resurrection guard so it can't grow unbounded across a session: this query
            // is authoritative, so any id we know we deleted that the server no longer returns has
            // had its delete honored and can be forgotten. Remove exactly those (not `intersect
            // present`) so a delete issued concurrently with this query — whose id isn't in this
            // snapshot — keeps its suppression.
            if (deleted.isNotEmpty()) {
                val present = fresh.mapTo(HashSet()) { it.uniqueId }
                val confirmedGone = deleted - present
                if (confirmedGone.isNotEmpty()) deletedIds.update { it - confirmedGone }
            }

            _isLoaded.value = true
            Logger.d(tag = TAG) { "loadAll: ${_contacts.value.size} contact(s)" }
        } catch (e: Exception) {
            // Leave _isLoaded untouched so ensureLoaded() will retry this session instead of being
            // stuck with an empty list from a transient query failure.
            Logger.e(e, TAG) { "Failed to load contacts" }
        }
    }

    private suspend fun observeEvents() {
        // EventBus replays its last event (replay=1) to new subscribers; a stale replayed
        // SessionEnded would reset() and race loadAll(). Skip whatever is already buffered and act
        // on live events only.
        val replayed = eventBus.events.replayCache.size
        eventBus.events.drop(replayed).collect { event ->
            when (event) {
                is BackendEvent.SessionEnded -> reset()

                is BackendEvent.DataEvent.BatchReceived -> {
                    if (event.driveId != driveId) return@collect
                    for (file in event.batchData) {
                        val contact = file.toContact() ?: continue
                        if (contact.uniqueId in deletedIds.value) continue
                        upsert(contact)
                    }
                }

                is BackendEvent.DriveEvent.Stopped -> {
                    if (event.driveId != driveId) return@collect
                    if (event.totalCount > 0) {
                        try {
                            loadAll()
                        } catch (e: Exception) {
                            Logger.e(e, TAG) { "post-Stopped reload failed: ${e.message}" }
                        }
                    }
                }

                else -> {}
            }
        }
    }

    private fun upsert(contact: Contact) {
        _contacts.update { current ->
            val idx = current.indexOfFirst { it.uniqueId == contact.uniqueId }
            if (idx >= 0) current.toMutableList().apply { this[idx] = contact }
            else current + contact
        }
    }

    /**
     * Fetches and parses a contact's on-demand `ext_data` payload (bios / rich text) — call only
     * when actually showing the bios; it is not part of the list/detail read.
     *
     * Returns null when there is nothing to show: the contact has no `ext_data` payload (its key is
     * absent from [Contact.payloadKeys], or the fetch 404s), the row is optimistic (no
     * [Contact.fileId] yet), or the fetch/parse fails. Callers treat null as "empty extended data".
     */
    suspend fun loadExtData(contact: Contact): ContactExtData? {
        if (ContactsProvider.CONTACT_EXT_DATA_PAYLOAD_KEY !in contact.payloadKeys) return null
        val fileId = contact.fileId ?: return null
        val keyHeader = payloadKeyHeader(contact, ContactsProvider.CONTACT_EXT_DATA_PAYLOAD_KEY)
            ?: return null

        val bytes = try {
            contactPayloadReader.getPayloadBytes(
                driveId = driveId,
                fileId = fileId,
                key = ContactsProvider.CONTACT_EXT_DATA_PAYLOAD_KEY,
                keyHeader = keyHeader,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "loadExtData fetch failed for ${contact.uniqueId}" }
            return null
        } ?: return null

        return runCatching {
            OdinSystemSerializer.deserialize<ContactExtData>(bytes.decodeToString())
        }.getOrElse {
            Logger.w(it, TAG) { "loadExtData parse failed for ${contact.uniqueId}" }
            null
        }
    }

    /**
     * Fetches and decrypts an arbitrary named payload from a contact file (e.g. the image referenced
     * by an Experience attribute's `experience_image` key). Returns the raw decrypted bytes, or null
     * when the [payloadKey] isn't present, the row is optimistic (no [Contact.fileId]), or the
     * fetch fails. Like [loadExtData], call only on demand.
     */
    suspend fun loadPayloadBytes(contact: Contact, payloadKey: String): ByteArray? {
        if (payloadKey.isBlank() || payloadKey !in contact.payloadKeys) return null
        val fileId = contact.fileId ?: return null
        val keyHeader = payloadKeyHeader(contact, payloadKey) ?: return null
        return try {
            contactPayloadReader.getPayloadBytes(driveId, fileId, payloadKey, keyHeader)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "loadPayloadBytes($payloadKey) failed for ${contact.uniqueId}" }
            null
        }
    }

    /**
     * The [KeyHeader] for decrypting one of [contact]'s payloads: the file's AES key paired with that
     * payload's **own** IV ([PayloadDescriptor.iv]) — not the file/content IV. Decrypting an encrypted
     * payload with the wrong IV corrupts its first 16-byte block (e.g. breaks the leading `{` of the
     * ext_data JSON). Falls back to the file key header when the payload is unencrypted (no IV), where
     * the IV is ignored anyway. Null when the file key header is missing (optimistic row).
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun payloadKeyHeader(contact: Contact, payloadKey: String): KeyHeader? {
        val fileKey = contact.keyHeader ?: return null
        val iv = contact.payloads.firstOrNull { it.key == payloadKey }?.iv
            ?.let { runCatching { Base64.decode(it) }.getOrNull() }
            ?: return fileKey
        return KeyHeader(iv = iv, aesKey = fileKey.aesKey)
    }

    // ------------------------------------------------------------
    // Write (V2 controller) — each applies an optimistic update
    // ------------------------------------------------------------

    /**
     * Create-or-update via the provider's bounded merge-and-retry flow. Pass [knownUniqueId] +
     * [knownVersionTag] for an edit (UPDATE), omit for a new contact (CREATE→UPDATE on 409).
     * Returns the new id/versionTag, or null on a generic failure. Rethrows [ForbiddenException]
     * (403) so callers can explain the missing-permission cause.
     */
    suspend fun save(
        content: ContactContent,
        knownUniqueId: Uuid? = null,
        knownVersionTag: Uuid? = null,
    ): ContactWriteResponse? {
        val response = try {
            contactsProvider.saveContact(content, knownUniqueId, knownVersionTag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ForbiddenException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "saveContact failed" }
            return null
        }

        deletedIds.update { it - response.uniqueId }
        val existingImage = _contacts.value.firstOrNull { it.uniqueId == response.uniqueId }?.image
        upsert(Contact(response.uniqueId, response.versionTag, content, existingImage))
        return response
    }

    /**
     * Soft-delete. Optimistically removes the contact; on a generic failure it reloads to restore
     * truth. Returns true on success (or already-gone). Rethrows [ForbiddenException] (403).
     */
    suspend fun delete(uniqueId: Uuid): Boolean {
        deletedIds.update { it + uniqueId }
        _contacts.update { current -> current.filterNot { it.uniqueId == uniqueId } }
        return try {
            contactsProvider.deleteContact(uniqueId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ForbiddenException) {
            deletedIds.update { it - uniqueId }
            loadAll()
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "deleteContact failed for $uniqueId" }
            deletedIds.update { it - uniqueId }
            loadAll()
            false
        }
    }

    /** Best-effort server-side enrichment of a connected identity from its public profile. */
    suspend fun sync(odinId: OdinId) {
        // A prior delete of this same identity left its uniqueId in the resurrection guard. The
        // server (re-)creates the contact under uniqueId = md5(odinId), so lift the suppression for
        // that id first — otherwise the re-created contact's incoming batch would be dropped.
        // Md5.toGuidId mirrors the server's hash; domainName is already lower-cased/normalized.
        deletedIds.update { it - Md5.toGuidId(odinId.domainName) }
        try {
            contactsProvider.syncContact(odinId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "syncContact failed for $odinId" }
        }
    }

    /**
     * Uploads (client-encrypts) an avatar for an existing contact via the provider's version-gated
     * image endpoint. Returns true on success. The updated row (with the image payload) lands via
     * drive sync.
     */
    suspend fun setImage(
        uniqueId: Uuid,
        bytes: ByteArray,
        contentType: String,
        versionTag: Uuid,
    ): Boolean = try {
        contactsProvider.setContactImage(
            uniqueId = uniqueId,
            contactDriveId = driveId,
            imageBytes = bytes,
            contentType = contentType,
            versionTag = versionTag,
        ) is ContactWriteResult.Ok
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(e, TAG) { "setContactImage failed for $uniqueId" }
        false
    }

    // ------------------------------------------------------------
    // Per-app app-data (two tiers) — write-through + bulk read
    // ------------------------------------------------------------
    //
    // [appId] is this app's id; it is used only to address the local slot (the inline optimistic
    // patch / the bulk-read map key) — it is never sent on the wire (the server stamps it from the
    // token). Pass it dashless or hyphenated; it's normalized to the canonical map-key form.

    /**
     * Inline-tier write (≤ 200 bytes). On success optimistically patches the live contact's
     * [ContactContent.appData] and adopts the returned versionTag; the authoritative row lands via
     * drive sync. Returns the new id/versionTag, or null on a generic failure. Rethrows
     * [ForbiddenException] (403) and [ContactAppDataTooLargeException] (blob over the tier cap — use
     * [setAppExtData] instead).
     */
    suspend fun setAppData(
        uniqueId: Uuid,
        appId: String,
        content: String,
        versionTag: Uuid,
    ): ContactWriteResponse? {
        val response = runAppDataWrite("setAppData", uniqueId) {
            contactsProvider.setContactAppData(uniqueId, content, versionTag)
        } ?: return null
        patchInlineAppData(uniqueId, appId, content, response.versionTag)
        return response
    }

    /** Inline-tier delete. Optimistically removes this app's slot. Same error contract as [setAppData]. */
    suspend fun deleteAppData(
        uniqueId: Uuid,
        appId: String,
        versionTag: Uuid,
    ): ContactWriteResponse? {
        val response = runAppDataWrite("deleteAppData", uniqueId) {
            contactsProvider.deleteContactAppData(uniqueId, versionTag)
        } ?: return null
        patchInlineAppData(uniqueId, appId, null, response.versionTag)
        return response
    }

    /**
     * Bulk-tier write (≤ 256 KB), stored as the `appextdata` payload. No optimistic list patch — the
     * bulk slot isn't in the contacts list; read it back with [loadAppExtData]. Same error contract as
     * [setAppData].
     */
    suspend fun setAppExtData(
        uniqueId: Uuid,
        content: String,
        versionTag: Uuid,
    ): ContactWriteResponse? = runAppDataWrite("setAppExtData", uniqueId) {
        contactsProvider.setContactAppExtData(uniqueId, content, versionTag)
    }

    /** Bulk-tier delete. Same error contract as [setAppData]. */
    suspend fun deleteAppExtData(
        uniqueId: Uuid,
        versionTag: Uuid,
    ): ContactWriteResponse? = runAppDataWrite("deleteAppExtData", uniqueId) {
        contactsProvider.deleteContactAppExtData(uniqueId, versionTag)
    }

    /**
     * Bulk-tier read: fetches + decrypts the `appextdata` payload and returns this app's opaque
     * string, or null when absent (no payload, the row is optimistic, or fetch/parse fails). Mirrors
     * [loadExtData].
     */
    suspend fun loadAppExtData(contact: Contact, appId: String): String? {
        if (ContactsProvider.CONTACT_APP_EXT_DATA_PAYLOAD_KEY !in contact.payloadKeys) return null
        val fileId = contact.fileId ?: return null
        val keyHeader = contact.keyHeader ?: return null

        val bytes = try {
            contactPayloadReader.getPayloadBytes(
                driveId = driveId,
                fileId = fileId,
                key = ContactsProvider.CONTACT_APP_EXT_DATA_PAYLOAD_KEY,
                keyHeader = keyHeader,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "loadAppExtData fetch failed for ${contact.uniqueId}" }
            return null
        } ?: return null

        return runCatching {
            OdinSystemSerializer.deserialize<ContactAppExtData>(bytes.decodeToString())
                .appData[appId.toCanonicalAppId()]
        }.getOrElse {
            Logger.w(it, TAG) { "loadAppExtData parse failed for ${contact.uniqueId}" }
            null
        }
    }

    /**
     * Runs a provider app-data write and unwraps it. Returns the [ContactWriteResponse] on success,
     * null on a generic failure / 404 / exhausted contention. Rethrows [ForbiddenException] and maps
     * the size-cap 400 ([ClientException] with [OdinClientErrorCode.MaxContentLengthExceeded]) to
     * [ContactAppDataTooLargeException].
     */
    private suspend fun runAppDataWrite(
        op: String,
        uniqueId: Uuid,
        call: suspend () -> ContactWriteResult,
    ): ContactWriteResponse? {
        val result = try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ForbiddenException) {
            throw e
        } catch (e: ClientException) {
            if (e.errorCode == OdinClientErrorCode.MaxContentLengthExceeded) {
                throw ContactAppDataTooLargeException(
                    e.message ?: "app-data blob exceeds the tier size cap",
                )
            }
            Logger.w(e, TAG) { "$op failed for $uniqueId (400 ${e.errorCode})" }
            return null
        } catch (e: Exception) {
            Logger.w(e, TAG) { "$op failed for $uniqueId" }
            return null
        }
        return when (result) {
            is ContactWriteResult.Ok -> result.body
            // retryVersionGated only surfaces Ok/NotFound; Conflict can't reach here, but the `when`
            // must be exhaustive. Both non-Ok cases are "nothing written" → null.
            ContactWriteResult.NotFound -> null
            is ContactWriteResult.Conflict -> null
        }
    }

    /** Optimistically sets ([content] != null) or clears this app's inline slot on the live contact. */
    private fun patchInlineAppData(uniqueId: Uuid, appId: String, content: String?, newTag: Uuid) {
        val key = appId.toCanonicalAppId()
        _contacts.update { current ->
            val idx = current.indexOfFirst { it.uniqueId == uniqueId }
            if (idx < 0) return@update current
            val existing = current[idx]
            val map = existing.content.appData ?: emptyMap()
            val newMap = if (content == null) map - key else map + (key to content)
            current.toMutableList().apply {
                this[idx] = existing.copy(
                    content = existing.content.copy(appData = newMap.ifEmpty { null }),
                    versionTag = newTag,
                )
            }
        }
    }
}
