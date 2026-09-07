package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileStateFilter
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.ExportDestination
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadDownloadService
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.coroutines.supervisedScope
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.settings.UserPreferences
import id.homebase.core.util.AlbumAccessDeniedException
import id.homebase.core.util.AlbumSaver
import id.homebase.core.util.NetworkMonitor
import id.homebase.core.util.extensionForMimeType
import id.homebase.core.util.saveAwait
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlin.io.encoding.Base64

private const val TAG = "ChatMediaAutoSave"

/** Newest messages re-examined after a silent DriveSync round. */
private const val RECENT_SCAN_LIMIT = 200

/**
 * Downloads incoming chat photos and videos in the background and writes them to the device
 * album, so the album is complete without the user opening anything.
 *
 * Listens on both receive lanes, because either alone misses most media: live WS pushes arrive as
 * [BackendEvent.DataEvent.BatchReceived], while everything that landed while the app was closed
 * comes in through the deliberately silent REST DriveSync, which only announces
 * [BackendEvent.DriveEvent.Stopped].
 */
class ChatMediaAutoSaveService(
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val payloadDownloadService: PayloadDownloadService,
    private val fileOperationsProvider: FileOperationsProvider,
    private val albumSaver: AlbumSaver,
    private val networkMonitor: NetworkMonitor,
    private val userPreferences: UserPreferences,
) {
    private val scope = supervisedScope("chat-media-autosave", ioDispatcher)
    private val chatDrive = chatTargetDrive.alias

    // One run at a time: overlapping runs would race the marker read/write and duplicate saves.
    private val runLock = Mutex()

    init {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is BackendEvent.DataEvent.BatchReceived -> {
                        if (event.driveId != chatDrive) return@collect
                        scope.launch { saveAll(event.batchData) }
                    }

                    is BackendEvent.DriveEvent.Stopped -> {
                        if (event.driveId != chatDrive || event.totalCount == 0) return@collect
                        scope.launch { saveRecent() }
                    }

                    else -> {}
                }
            }
        }
    }

    private suspend fun saveRecent() {
        if (!userPreferences.autoSaveIncomingMedia) return
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return
        val result = QueryBatch(identityId).queryBatchAsync(
            dbm = dbm,
            driveId = chatDrive,
            noOfItems = RECENT_SCAN_LIMIT,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.UserDate,
            fileSystemType = 0,
            fileState = FileStateFilter.Active,
            filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
        )
        saveAll(result.records)
    }

    private suspend fun saveAll(files: List<HomebaseFile>) = runLock.withLock {
        val enabled = userPreferences.autoSaveIncomingMedia
        if (!enabled) return@withLock
        val self = credentialsManager.getActiveCredentials()?.domain ?: return@withLock
        val unmeteredOnly = userPreferences.autoSaveOnUnmeteredOnly
        val unmetered = networkMonitor.isUnmetered()
        val enabledSince = userPreferences.autoSaveIncomingMediaSince

        try {
            for (file in files) {
                if (file.fileMetadata.appData.fileType != ChatProtocol.MessageFileType) continue
                val author = file.fileMetadata.originalAuthor
                // A null author is a self-authored row the server never stamped.
                val isIncoming = author != null && author != self
                for (payload in file.fileMetadata.payloads.orEmpty()) {
                    val alreadySaved =
                        dbm.autoSavedMedia.isSaved(file.fileId.toString(), payload.key)
                    val save = shouldAutoSave(
                        payload = payload,
                        isIncoming = isIncoming,
                        isSoftDeleted = file.isSoftDeleted(),
                        autoSaveEnabled = enabled,
                        unmeteredOnly = unmeteredOnly,
                        isUnmetered = unmetered,
                        alreadySaved = alreadySaved,
                        messageTimestampMs = file.fileMetadata.appData.userDate
                            ?: file.fileMetadata.created.milliseconds,
                        enabledSinceMs = enabledSince,
                    )
                    if (save) saveOne(file, payload)
                }
            }
        } catch (e: AlbumAccessDeniedException) {
            Logger.w(tag = TAG) { "Album not writable, abandoning this batch: ${e.message}" }
        }
    }

    private suspend fun saveOne(file: HomebaseFile, payload: PayloadDescriptor) {
        val iv = payload.iv ?: return
        val extension = payload.contentType?.let { extensionForMimeType(it) } ?: "bin"
        val name = "homebase_${file.fileId}_${payload.key}.$extension"

        val path = payloadDownloadService.exportToTemp(
            driveId = chatDrive,
            fileId = file.fileId,
            key = payload.key,
            keyHeader = KeyHeader(Base64.decode(iv), file.keyHeader.aesKey),
            destination = ExportDestination.CacheRoot("hbautosave_", ".$extension"),
        ) ?: return

        try {
            // The export lands in cacheDir, which the CacheSweeper reaps — hand it straight over
            // and drop it, rather than treating it as the saved copy.
            val location = albumSaver.saveAwait(Path(path), name)
            dbm.autoSavedMedia.markSaved(file.fileId.toString(), payload.key)
            Logger.d(tag = TAG) { "Saved ${payload.key} of ${file.fileId} to $location" }
        } catch (e: AlbumAccessDeniedException) {
            throw e
        } catch (e: Exception) {
            Logger.w(throwable = e, tag = TAG) { "Auto-save failed for ${payload.key}" }
        } finally {
            runCatching { fileOperationsProvider.deleteTempFile(path) }
        }
    }
}
