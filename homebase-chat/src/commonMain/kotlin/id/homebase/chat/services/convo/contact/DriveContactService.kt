package id.homebase.chat.services.convo.contact

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.BatchResult
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.config.contactTargetDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DriveContactService(
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "DriveContactService"
    }

    private val contactDrive = contactTargetDrive.alias
    private val _contacts = MutableStateFlow<List<ContactUiModel>>(emptyList())

    private val contactByOdinId =
        MutableStateFlow<Map<OdinId, ContactUiModel>>(emptyMap())

    val contacts: StateFlow<List<ContactUiModel>> = _contacts.asStateFlow()

    init {
        scope.launch {
            eventBus.events.collect { event ->
                // Two refresh triggers, by design — after the silent-DriveSync (e7f46062)
                // and pure-push chat-drive (736b4f07) refactors, contact rows land via two
                // event classes and we must converge from both:
                //
                //   1) DriveEvent.Stopped(totalCount > 0): catch-up after a sync round
                //      (cold-boot, reconnect). Gate on totalCount > 0 ALONE (not on
                //      result == Success): DriveSync writes each batch to DriveMainIndex
                //      before the next batch starts, so totalCount > 0 with Failure still
                //      means real contact rows landed. totalCount == 0 means cursor at
                //      HEAD: nothing to refresh.
                //
                //   2) DataEvent.BatchReceived: live pure-push WS upserts from
                //      DriveWebSocketUpsertWorker (and in-process OptimisticWriter).
                //      Without this branch, a contact arriving over the open WS (e.g.
                //      a just-accepted connection request) lands in DriveMainIndex but
                //      the _contacts StateFlow stays stale until next app restart.
                //
                // Both branches scope.launch { refresh() } — never call refresh() inline:
                // QueryBatch hangs on partial connectivity, which would park the EventBus
                // buffer and cascade into stalling the chat Send path.
                if (event is BackendEvent.SessionEnded) {
                    reset()
                } else if (event is BackendEvent.DriveEvent.Stopped &&
                    event.driveId == contactDrive &&
                    event.totalCount > 0
                ) {
                    scope.launch { refresh() }
                } else if (event is BackendEvent.DataEvent.BatchReceived &&
                    event.driveId == contactDrive &&
                    event.batchData.isNotEmpty()
                ) {
                    scope.launch { refresh() }
                }
            }
        }
    }

    private var startJob: Job? = null

    /**
     * Logout: cancel any in-flight refresh and drop the previous identity's
     * contacts. ContactService (which combines this flow) re-emits empty
     * automatically. The next session repopulates via the contact-drive sync.
     */
    fun reset() {
        startJob?.cancel()
        startJob = null
        _contacts.value = emptyList()
        contactByOdinId.value = emptyMap()
    }

    fun start() {
        if (startJob?.isActive == true) return
        startJob = scope.launch {
            refresh()
        }
    }

    fun resolveByOdinId(odinId: OdinId): ContactUiModel? {
        return contactByOdinId.value[odinId]
    }

    private suspend fun refresh() {
        val result = fetchContacts()
        // The contact drive can hold more than one file row for the same
        // identity (e.g. a row from the connection-accept flow plus one from a
        // later public-profile fetch). Records arrive NewestFirst, so
        // distinctBy keeps the freshest row per odinId and drops the stale
        // duplicate — otherwise the duplicate entry flows into every consumer
        // of `contacts`. Chat pickers each defend with their own
        // `.distinctBy { it.odinId }`; the moments audience picker did not, so
        // it surfaced the duplicate. Dedupe once here at the source.
        val deduped = result.records.distinctBy { it.odinId }
        _contacts.value = deduped
        contactByOdinId.value =
            deduped.associateBy { it.odinId }
    }


    suspend fun fetchContacts(
        limit: Int = 1000,
        cursor: QueryBatchCursor? = null
    ): BatchResult<ContactUiModel> {

        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        val result =
            queryBatch.queryBatchAsync(
                dbm = dbm,
                driveId = contactDrive,
                noOfItems = limit,
                cursor = cursor,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(ChatProtocol.ContactFileType)
            )

        return BatchResult(
            records = result.records.mapNotNull { mapToContact(it) },
            hasMoreRows = result.hasMoreRows,
            cursor = result.cursor
        )
    }

    private suspend fun mapToContact(header: HomebaseFile): ContactUiModel? {
        val metadata = header.fileMetadata
        val appData = metadata.appData

        val uid = appData.uniqueId
        if (uid == null) {
            Logger.e("Contact found with null uniqueId")
            return null
        }

        val content = appData.content ?: ""
        val parsedContact = try {
            OdinSystemSerializer.deserialize<ContactServerFile>(content)
        } catch (e: Exception) {
            Logger.e(e) { "Failed to deserialize contact content for uid=$uid: ${content.take(200)}" }
            return null
        }

        return ContactUiModel(
            id = uid,
            odinId = parsedContact.odinId
                ?: throw IllegalStateException("why is the odin id missing?"),
            name = parsedContact.name.displayName?.takeIf { it.isNotBlank() }
                ?: parsedContact.odinId.domainName,
            avatarInitials = parsedContact.name.initials(),
            avatarUrl = "https://${parsedContact.odinId}/pub/image"
        )
    }
}
