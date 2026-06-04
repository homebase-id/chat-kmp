@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.config.vaultLabeledDrive
import id.homebase.core.ui.screens.vault.model.VAULT_FILE_TYPE
import id.homebase.core.ui.screens.vault.model.VAULT_SECTION_TYPE
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.ui.screens.vault.model.VaultSection
import id.homebase.core.ui.screens.vault.model.VaultSectionContent
import id.homebase.core.ui.screens.vault.model.toVaultEntry
import id.homebase.core.ui.screens.vault.model.toVaultSectionModel
import id.homebase.core.ui.screens.vault.model.toVaultSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "VaultStream"

/**
 * Real-time observation service for vault data. Incremental in-memory updates
 * driven by [EventBus] events, following the same pattern as
 * [id.homebase.chat.services.convo.ConversationStream].
 *
 * State is exposed via two [StateFlow]s:
 * - [sections] — all vault sections sorted by sortOrder (without entries)
 * - [entriesBySection] — entries grouped by their section's uniqueId
 *
 * The ViewModel combines these two flows to produce the full UI state.
 */
class VaultStream(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {
    private val driveId = vaultLabeledDrive.drive.alias

    private val _sections = MutableStateFlow<List<VaultSection>>(emptyList())
    val sections: StateFlow<List<VaultSection>> = _sections.asStateFlow()

    private val _entriesBySection = MutableStateFlow<Map<Uuid, List<VaultEntry>>>(emptyMap())
    val entriesBySection: StateFlow<Map<Uuid, List<VaultEntry>>> = _entriesBySection.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _pendingPageDeletes = MutableStateFlow<Map<Uuid, Set<String>>>(emptyMap())

    private val deletedSectionIds = mutableSetOf<Uuid>()
    private val deletedEntryIds = mutableSetOf<Uuid>()

    init {
        scope.launch { observeEvents() }
    }

    /**
     * Load vault data from the local DB. Must be called after authentication
     * (from [onPostAuthenticated]) so the singleton picks up the new user's
     * data instead of retaining stale state from a previous session.
     */
    fun start() {
        scope.launch { loadAll() }
    }

    /**
     * Clear all in-memory state so a subsequent [start] loads cleanly for
     * a different identity. Called during logout before the DB wipe.
     */
    fun reset() {
        _sections.value = emptyList()
        _entriesBySection.value = emptyMap()
        _isLoaded.value = false
        _pendingPageDeletes.value = emptyMap()
        deletedSectionIds.clear()
        deletedEntryIds.clear()
    }

    // region Cold load

    /**
     * Cold-load all vault data from the local DB. Called on init and after
     * outbox completion/failure events where we need DB-confirmed state.
     */
    suspend fun loadAll() {
        val creds = credentialsManager.getActiveCredentials() ?: run {
            _isLoaded.value = true
            return
        }
        val identityId = creds.getIdentityId()
        val queryBatch = QueryBatch(identityId)

        try {
            val result = queryBatch.queryBatchAsync(
                dbm = databaseManager,
                driveId = driveId,
                noOfItems = 1100,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = FileSystemType.Standard.value,
                filetypesAnyOf = listOf(VAULT_SECTION_TYPE, VAULT_FILE_TYPE),
            )

            val (sectionRecords, fileRecords) = result.records.partition {
                it.fileMetadata.appData.fileType == VAULT_SECTION_TYPE
            }

            // Map section records to (HomebaseFile, VaultSectionContent) pairs
            val sectionPairs = sectionRecords.mapNotNull { file ->
                val content = file.toVaultSectionModel() ?: return@mapNotNull null
                file to content
            }

            // Map file records to VaultEntry
            val allEntries = fileRecords.mapNotNull { it.toVaultEntry() }
            val groupedEntries = allEntries.filter { it.groupId != null }.groupBy { it.groupId!! }

            val sortedPairs = sectionPairs
                .filter { (file, _) ->
                    val id = file.fileMetadata.appData.uniqueId ?: file.fileId
                    id !in deletedSectionIds
                }
                .sortedBy { it.second.sortOrder }
            val sectionModels = sortedPairs.mapIndexed { index, pair ->
                pair.toVaultSection(
                    isFirst = index == 0,
                    isLast = index == sortedPairs.size - 1,
                )
            }

            val filteredEntries = groupedEntries
                .filterKeys { it !in deletedSectionIds }
                .mapValues { (_, entries) ->
                    entries.filterNot { it.uniqueId in deletedEntryIds }
                }

            _sections.value = sectionModels
            _entriesBySection.value = filteredEntries

            Logger.d(tag = TAG) {
                "loadAll: ${sectionModels.size} section(s), ${allEntries.size} entry(ies)"
            }
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to load vault data" }
        }

        _isLoaded.value = true
    }

    // endregion

    // region Event observation

    private suspend fun observeEvents() {
        eventBus.events.collect { event ->
            when (event) {
                // Logout: drop the previous identity's vault sections/entries.
                is BackendEvent.SessionEnded -> reset()
                is BackendEvent.DataEvent.BatchReceived -> {
                    if (event.driveId != driveId) return@collect
                    // Every BatchReceived is a live WS-push event (DriveSync is
                    // silent — see DriveSync.kt). Drives incremental section/entry
                    // updates in steady state.
                    processBatchIncrementally(event.batchData)
                }

                is BackendEvent.DriveEvent.Stopped -> {
                    if (event.driveId != driveId) return@collect
                    // Silent-DriveSync contract: when the vault drive finishes
                    // a DriveSync round, reload the full vault state from DB so
                    // sections + entries reflect the just-landed files.
                    //
                    // Gate on totalCount > 0 ALONE (not on result == Success):
                    // DriveSync writes each batch to DriveMainIndex before the
                    // next batch starts, so Stopped(Aborted, totalCount > 0)
                    // still means real vault rows landed — we want to surface
                    // them rather than wait for the next round (which on
                    // PermissionDenied may never come). totalCount == 0 means
                    // cursor at HEAD: nothing to reload.
                    if (event.totalCount > 0) {
                        try {
                            loadAll()
                        } catch (e: Exception) {
                            Logger.e(e) {
                                "VaultStream: post-Stopped reload FAILED: ${e.message}"
                            }
                        }
                    }
                }

                is BackendEvent.OutboxEvent.ItemFailed -> {
                    if (event.driveId != driveId) return@collect
                    markEntryFailed(event.uniqueId)
                }

                is BackendEvent.OutboxEvent.OutboxItemDropped -> {
                    if (event.driveId != driveId) return@collect
                    markEntryFailed(event.uniqueId)
                }

                else -> {}
            }
        }
    }

    private fun markEntryFailed(uniqueId: Uuid) {
        _entriesBySection.update { current ->
            current.mapValues { (_, entries) ->
                entries.map { entry ->
                    if (entry.uniqueId == uniqueId && entry.uploadStatus != null) {
                        entry.copy(uploadStatus = VaultUploadStatus.Failed("Upload failed"))
                    } else {
                        entry
                    }
                }
            }
        }
    }

    private fun processBatchIncrementally(batchData: List<HomebaseFile>) {
        for (file in batchData) {
            when (file.fileMetadata.appData.fileType) {
                VAULT_SECTION_TYPE -> upsertSection(file)
                VAULT_FILE_TYPE -> upsertEntry(file)
            }
        }
    }

    // endregion

    // region Incremental updates

    private fun upsertSection(file: HomebaseFile) {
        val content = file.toVaultSectionModel() ?: return
        val sectionId = file.fileMetadata.appData.uniqueId ?: file.fileId
        if (sectionId in deletedSectionIds) return

        _sections.update { current ->
            val mutable = current.toMutableList()
            val idx = mutable.indexOfFirst { it.sectionId == sectionId }
            val updated = (file to content).toVaultSection()
            if (idx >= 0) mutable[idx] = updated else mutable.add(updated)

            val sorted = mutable.sortedBy { it.sortOrder }
            sorted.mapIndexed { index, section ->
                section.copy(isFirst = index == 0, isLast = index == sorted.size - 1)
            }
        }
    }

    private fun upsertEntry(file: HomebaseFile) {
        val entry = file.toVaultEntry() ?: return
        val sectionId = entry.groupId ?: return
        if (entry.uniqueId in deletedEntryIds) return
        if (sectionId in deletedSectionIds) return

        val pendingDeletes = _pendingPageDeletes.value[entry.uniqueId].orEmpty()

        _entriesBySection.update { current ->
            val list = current[sectionId]?.toMutableList() ?: mutableListOf()
            val idx = list.indexOfFirst { it.uniqueId == entry.uniqueId }
            if (idx >= 0) {
                val existing = list[idx]
                val pendingAppends = existing.payloadDescriptors.filter { desc ->
                    desc.iv == null && entry.payloadDescriptors.none { it.key == desc.key }
                }
                val filteredDescriptors = if (pendingDeletes.isNotEmpty()) {
                    entry.payloadDescriptors.filter { it.key !in pendingDeletes }
                } else {
                    entry.payloadDescriptors
                }
                list[idx] = entry.copy(payloadDescriptors = filteredDescriptors + pendingAppends)
            } else {
                list.add(entry)
            }
            current + (sectionId to list.toList())
        }

        if (pendingDeletes.isNotEmpty()) {
            val serverKeys = entry.payloadDescriptors.map { it.key }.toSet()
            val confirmed = pendingDeletes.filter { it !in serverKeys }
            if (confirmed.isNotEmpty()) {
                _pendingPageDeletes.update { current ->
                    val remaining = current[entry.uniqueId].orEmpty() - confirmed.toSet()
                    if (remaining.isEmpty()) current - entry.uniqueId
                    else current + (entry.uniqueId to remaining)
                }
            }
        }
    }

    // endregion

    // region Optimistic mutations

    /**
     * Insert a section optimistically (before the outbox confirms).
     * Called by VaultService after creating a section.
     */
    fun insertOptimisticSection(section: VaultSection) {
        if (section.sectionId in deletedSectionIds) return
        _sections.update { current ->
            if (current.any { it.sectionId == section.sectionId }) return@update current
            val mutable = current.toMutableList()
            mutable.add(section)
            val sorted = mutable.sortedBy { it.sortOrder }
            sorted.mapIndexed { index, s ->
                s.copy(isFirst = index == 0, isLast = index == sorted.size - 1)
            }
        }
    }

    fun updateOptimisticSection(updated: VaultSection) {
        _sections.update { current ->
            current.map { if (it.sectionId == updated.sectionId) updated.copy(isFirst = it.isFirst, isLast = it.isLast) else it }
        }
    }

    fun resortSections() {
        _sections.update { current ->
            val sorted = current.sortedBy { it.sortOrder }
            sorted.mapIndexed { index, section ->
                section.copy(isFirst = index == 0, isLast = index == sorted.size - 1)
            }
        }
    }

    /**
     * Insert an entry optimistically (before the outbox confirms).
     */
    fun insertOptimisticEntry(entry: VaultEntry, sectionId: Uuid) {
        if (entry.uniqueId in deletedEntryIds) return
        if (sectionId in deletedSectionIds) return
        _entriesBySection.update { current ->
            val list = current[sectionId]?.toMutableList() ?: mutableListOf()
            list.add(entry)
            current + (sectionId to list.toList())
        }
    }

    /**
     * Update an existing entry in-place. Used for upload progress updates
     * or metadata changes before the outbox round-trips.
     */
    fun updateOptimisticEntry(entry: VaultEntry) {
        val sectionId = entry.groupId ?: return

        _entriesBySection.update { current ->
            val list = current[sectionId]?.toMutableList() ?: return@update current
            val idx = list.indexOfFirst { it.uniqueId == entry.uniqueId }
            if (idx >= 0) {
                list[idx] = entry
                current + (sectionId to list.toList())
            } else {
                current
            }
        }
    }

    /**
     * Remove an entry from all sections. Called after a delete is enqueued.
     */
    fun removeEntry(uniqueId: Uuid) {
        deletedEntryIds += uniqueId
        _entriesBySection.update { current ->
            current.mapValues { (_, entries) ->
                entries.filterNot { it.uniqueId == uniqueId }
            }
        }
    }

    /**
     * Remove a section and all its entries. Called after a section delete is enqueued.
     */
    fun removeSection(sectionId: Uuid) {
        deletedSectionIds += sectionId
        val entryIds = _entriesBySection.value[sectionId]?.map { it.uniqueId }.orEmpty()
        deletedEntryIds += entryIds

        _entriesBySection.update { current ->
            current - sectionId
        }

        _sections.update { current ->
            val filtered = current.filterNot { it.sectionId == sectionId }
            filtered.mapIndexed { index, section ->
                section.copy(isFirst = index == 0, isLast = index == filtered.size - 1)
            }
        }
    }

    /**
     * Mark a payload key as pending delete so [upsertEntry] won't restore it
     * from stale DB data. Cleared automatically when the server confirms the
     * delete (the incoming entry no longer has the key).
     */
    fun markPayloadPendingDelete(entryId: Uuid, payloadKey: String) {
        _pendingPageDeletes.update { current ->
            current + (entryId to (current[entryId].orEmpty() + payloadKey))
        }
    }

    // endregion
}
