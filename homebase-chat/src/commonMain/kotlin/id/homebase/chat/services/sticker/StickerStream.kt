@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.services.sticker

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
import id.homebase.core.config.stickerLabeledDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "StickerStream"

/**
 * Real-time observation service for the user's saved stickers. Direct structural
 * copy of [id.homebase.core.ui.screens.vault.VaultStream], simplified for the flat
 * single-list "My Stickers" tray (no sections).
 *
 * State is exposed as [stickers] — a [StateFlow] of [SavedSticker], newest-first,
 * hydrated from the local DriveMainIndex via [QueryBatch] and kept live by
 * [EventBus] events (steady-state BatchReceived push + post-DriveSync reload).
 */
class StickerStream(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {
    private val driveId = stickerLabeledDrive.drive.alias

    private val _stickers = MutableStateFlow<List<SavedSticker>>(emptyList())
    val stickers: StateFlow<List<SavedSticker>> = _stickers.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    // uniqueIds removed optimistically; suppresses any stale DB row from re-appearing
    // until the server confirms the delete.
    private val deletedStickerIds = mutableSetOf<Uuid>()

    init {
        scope.launch { observeEvents() }
    }

    /** Cold-load saved stickers from the local DB. Safe to call repeatedly. */
    fun start() {
        scope.launch { loadAll() }
    }

    /** Clear in-memory state for a clean re-load on a different identity (logout). */
    fun reset() {
        _stickers.value = emptyList()
        _isLoaded.value = false
        deletedStickerIds.clear()
    }

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
                // Cap the tray; v1 is a flat library, not a paged surface.
                noOfItems = 500,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = FileSystemType.Standard.value,
                filetypesAnyOf = listOf(StickerProtocol.STICKER_FILE_TYPE),
            )

            val loaded = result.records
                .mapNotNull { it.toSavedSticker() }
                .filter { it.uniqueId !in deletedStickerIds }

            _stickers.value = loaded
            Logger.d(tag = TAG) { "loadAll: ${loaded.size} saved sticker(s)" }
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to load saved stickers" }
        }

        _isLoaded.value = true
    }

    private suspend fun observeEvents() {
        eventBus.events.collect { event ->
            when (event) {
                is BackendEvent.SessionEnded -> reset()

                is BackendEvent.DataEvent.BatchReceived -> {
                    if (event.driveId != driveId) return@collect
                    event.batchData.forEach(::upsertSticker)
                }

                is BackendEvent.DriveEvent.Stopped -> {
                    if (event.driveId != driveId) return@collect
                    // Same silent-DriveSync contract as VaultStream: reload on
                    // totalCount > 0 so just-landed sticker rows surface even on a
                    // partial/aborted round.
                    if (event.totalCount > 0) {
                        try {
                            loadAll()
                        } catch (e: Exception) {
                            Logger.e(e, TAG) { "post-Stopped reload FAILED: ${e.message}" }
                        }
                    }
                }

                else -> {}
            }
        }
    }

    private fun upsertSticker(file: HomebaseFile) {
        if (file.fileMetadata.appData.fileType != StickerProtocol.STICKER_FILE_TYPE) return
        val sticker = file.toSavedSticker() ?: return
        if (sticker.uniqueId in deletedStickerIds) return

        _stickers.update { current ->
            val mutable = current.toMutableList()
            val idx = mutable.indexOfFirst { it.uniqueId == sticker.uniqueId }
            // Server-confirmed rows clear the optimistic pending flag.
            val resolved = sticker.copy(isPending = false)
            if (idx >= 0) mutable[idx] = resolved else mutable.add(0, resolved)
            mutable.sortedByDescending { it.createdAt }
        }
    }

    /** Insert a sticker optimistically (before the outbox confirms). */
    fun insertOptimistic(sticker: SavedSticker) {
        if (sticker.uniqueId in deletedStickerIds) return
        _stickers.update { current ->
            if (current.any { it.uniqueId == sticker.uniqueId }) return@update current
            (listOf(sticker.copy(isPending = true)) + current)
                .sortedByDescending { it.createdAt }
        }
    }

    /** Remove a sticker locally. Called after a delete is enqueued. */
    fun removeOptimistic(uniqueId: Uuid) {
        deletedStickerIds += uniqueId
        _stickers.update { current -> current.filterNot { it.uniqueId == uniqueId } }
    }
}
