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

    init {
        scope.launch { loadAll() }
        scope.launch { observeEvents() }
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

            val sortedPairs = sectionPairs.sortedBy { it.second.sortOrder }
            val sectionModels = sortedPairs.mapIndexed { index, pair ->
                pair.toVaultSection(
                    isFirst = index == 0,
                    isLast = index == sortedPairs.size - 1,
                )
            }

            _sections.value = sectionModels
            _entriesBySection.value = groupedEntries

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
                is BackendEvent.DriveEvent.BatchReceived -> {
                    if (event.driveId != driveId) return@collect
                    processBatchIncrementally(event.batchData)
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

        _entriesBySection.update { current ->
            val list = current[sectionId]?.toMutableList() ?: mutableListOf()
            val idx = list.indexOfFirst { it.uniqueId == entry.uniqueId }
            if (idx >= 0) list[idx] = entry else list.add(entry)
            current + (sectionId to list.toList())
        }
    }

    // endregion

    // region Optimistic mutations

    /**
     * Insert a section optimistically (before the outbox confirms).
     * Called by VaultService after creating a section.
     */
    fun insertOptimisticSection(section: VaultSection) {
        _sections.update { current ->
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

    // endregion
}
