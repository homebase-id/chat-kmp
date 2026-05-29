package id.homebase.core.ui.screens.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.auth.toConnectionStatus
import id.homebase.core.config.momentsLabeledDrive
import id.homebase.core.moments.MomentsAlbumZoom
import id.homebase.core.moments.MomentsPreferences
import id.homebase.core.moments.MomentsViewMode
import id.homebase.core.moments.services.MomentActionService
import id.homebase.core.moments.services.MomentsFeedService
import id.homebase.core.moments.services.MomentsPostSenderService
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Thin wrapper over [MomentsFeedService] for the feed screen. The service is a
 * singleton started by `AppModule.onPostAuthenticated`, so the VM only
 * subscribes — no start() call here.
 *
 * Also surfaces the owner session and connection/sync status the screen needs
 * to render the chat-style header (avatar with status indicators).
 */
class MomentsFeedViewModel(
    feedService: MomentsFeedService,
    ownerSessionRepository: OwnerSessionRepository,
    authConnectionCoordinator: AuthConnectionCoordinator,
    eventBus: EventBus,
    postSender: MomentsPostSenderService,
    private val optimisticWriter: OptimisticWriter,
    private val actionService: MomentActionService,
    private val momentsPreferences: MomentsPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MomentsFeedUiState())
    val uiState: StateFlow<MomentsFeedUiState> = _uiState.asStateFlow()

    private val drive = momentsLabeledDrive.drive.alias

    companion object {
        private const val TAG = "MomentsFeedViewModel"
    }

    /**
     * Drop the per-moment upload status entry without removing the moment
     * from the feed. Called when the user dismisses a permanent-Failed
     * overlay — they're acknowledging the failure but keeping the local
     * copy around (compare [deleteFailedMoment], which also rolls back the
     * optimistic write so the moment disappears).
     */
    fun dismissUpload(momentId: Uuid) {
        _uiState.update { state ->
            state.copy(uploadProgress = (state.uploadProgress - momentId).toPersistentMap())
        }
    }

    /**
     * Roll back the optimistic write for a moment whose outbox upload was
     * permanently dropped, then clear the per-moment upload status entry.
     *
     * Why rollback rather than the normal "delete moment" path: a dropped
     * upload never reached the server, so there's nothing to delete remotely
     * and no recipients to notify — the only artefact is the local
     * `DriveMainIndex` row the optimistic write created. The semantically
     * correct undo is exactly what `OptimisticWriter.removeOptimisticFile`
     * does (it checks `isPendingSendTag` is still set so it can't
     * accidentally delete a successfully-delivered moment), and the
     * `OptimisticRollback` it emits is what `MomentsFeedService` is now
     * subscribed to for removing from the in-memory feed.
     */
    /**
     * Fire-and-forget reaction toggle from the feed (double/triple-tap
     * gestures on a moment card). Uses the same toggle semantics as the
     * detail screen — tap-to-add, tap-again-to-remove — so the visual
     * pulse in [id.homebase.core.ui.screens.moments.MomentsScreen] is the
     * user's confirmation that their tap registered, not a strict "added"
     * signal.
     */
    fun addReaction(momentId: Uuid, emoji: String) {
        viewModelScope.launch {
            try {
                actionService.toggleReactionOnMoment(momentId, emoji)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) {
                    "addReaction failed for moment=$momentId emoji=$emoji: ${t.message}"
                }
            }
        }
    }

    fun deleteFailedMoment(momentId: Uuid) {
        viewModelScope.launch {
            try {
                optimisticWriter.removeOptimisticFile(drive, momentId)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) {
                    "deleteFailedMoment: rollback failed for moment=$momentId"
                }
            }
            _uiState.update { state ->
                state.copy(uploadProgress = (state.uploadProgress - momentId).toPersistentMap())
            }
        }
    }

    fun setViewMode(mode: MomentsViewMode) {
        viewModelScope.launch {
            momentsPreferences.setViewMode(mode)
        }
    }

    /**
     * Update the album-view zoom level. Session-only — see [MomentsAlbumZoom]
     * for why this isn't routed through [MomentsPreferences].
     */
    fun setAlbumZoom(zoom: MomentsAlbumZoom) {
        _uiState.update { it.copy(albumZoom = zoom) }
    }

    init {
        // The service emits in its own canonical order (by userDate). The view
        // mode preference picks between Timeline (sort by post creation) and
        // Album (sort by userDate) — applying the sort here keeps the service
        // ignorant of UI preferences.
        viewModelScope.launch {
            var lastSize = -1
            var lastNewestId: String? = null
            combine(feedService.feed, momentsPreferences.viewMode) { list, mode ->
                list to mode
            }.collect { (list, mode) ->
                val sorted = when (mode) {
                    // Reels mirrors Timeline ordering — newest posted first.
                    MomentsViewMode.Timeline,
                    MomentsViewMode.Reels -> list.sortedByDescending { it.createdMs }
                    MomentsViewMode.Album -> list.sortedByDescending { it.userDateMs }
                }
                _uiState.update { it.copy(moments = sorted, viewMode = mode) }
                // Diagnostic: log every size/head change so we can confirm the
                // feed VM is propagating service updates into uiState. Pairs
                // with MomentsFeedService.processIncrementalBatch logs to
                // triangulate a "remote moment didn't appear" report.
                val newest = sorted.firstOrNull()
                val newestId = newest?.id?.toString()
                if (sorted.size != lastSize || newestId != lastNewestId) {
                    Logger.d(tag = TAG) {
                        "uiState moments updated: count=${sorted.size} mode=$mode " +
                            "newest=$newestId sender=${newest?.senderOdinId?.domainName ?: "self"}"
                    }
                    lastSize = sorted.size
                    lastNewestId = newestId
                }
            }
        }

        viewModelScope.launch {
            ownerSessionRepository.user.collect { session ->
                _uiState.update { it.copy(ownerSession = session) }
            }
        }

        viewModelScope.launch {
            authConnectionCoordinator.connectionState.collectLatest { state ->
                _uiState.update { it.copy(connectionStatus = state.toConnectionStatus()) }
            }
        }

        viewModelScope.launch {
            eventBus.events
                .filter {
                    it is BackendEvent.SyncAllStarted ||
                        it is BackendEvent.SyncAllStopped
                }
                .collectLatest { event ->
                    when (event) {
                        is BackendEvent.SyncAllStarted -> _uiState.update {
                            it.copy(driveIsSyncing = true, hasDriveError = false)
                        }

                        is BackendEvent.SyncAllStopped -> _uiState.update {
                            it.copy(
                                driveIsSyncing = false,
                                hasDriveError = event.result is BackendEvent.SyncAllResult.Failure,
                            )
                        }

                        else -> Unit
                    }
                }
        }

        // Mirror the post sender's transient source-image cache into UiState
        // so the tile renderer can show the user's selected photo immediately
        // during the "Preparing…" window (placeholder row has no payloads /
        // embedded thumbnail yet). The cache empties as soon as the real
        // optimistic update lands the embedded thumbnail on the row.
        viewModelScope.launch {
            postSender.pendingLocalPreviews.collect { previews ->
                _uiState.update { it.copy(pendingLocalPreviews = previews) }
            }
        }

        // Initial Preparing state for a freshly posted moment. The placeholder
        // write fires BatchReceived (tile appears) and then we get this event,
        // which puts the overlay in UploadStatus.Preparing until ItemEnqueued
        // promotes it to Sending. Without this collector the placeholder tile
        // would have no overlay until the outbox got round to enqueueing.
        viewModelScope.launch {
            eventBus.events.filter { it is BackendEvent.PayloadBundlingEvent.Preparing }
                .collect { event ->
                    event as BackendEvent.PayloadBundlingEvent.Preparing
                    _uiState.update { state ->
                        state.copy(
                            uploadProgress = (state.uploadProgress + (event.uniqueId to UploadStatus.Preparing)).toPersistentMap()
                        )
                    }
                }
        }

        // Track per-moment upload progress via outbox and payload bundling
        // events. Mirrors `ConversationListViewModel`'s collectors — the
        // EventBus events fire globally keyed by `uniqueId`, and moments use
        // `momentUniqueId` as the file's uniqueId, so the lookups line up.
        viewModelScope.launch {
            eventBus.events.filter { it is BackendEvent.PayloadBundlingEvent.Video.PhaseProgress }
                .collect { event ->
                    event as BackendEvent.PayloadBundlingEvent.Video.PhaseProgress
                    _uiState.update { state ->
                        state.copy(
                            uploadProgress = (state.uploadProgress + (event.uniqueId to UploadStatus.Processing(
                                progress = event.progress,
                                phase = event.phase,
                            ))).toPersistentMap()
                        )
                    }
                }
        }

        viewModelScope.launch {
            eventBus.events.filter { it is BackendEvent.OutboxEvent.ItemProgress }
                .collect { event ->
                    event as BackendEvent.OutboxEvent.ItemProgress
                    _uiState.update { state ->
                        state.copy(
                            uploadProgress = (state.uploadProgress + (event.uniqueId to UploadStatus.Uploading(
                                event.progress / 100f
                            ))).toPersistentMap()
                        )
                    }
                }
        }

        // Once the moment is durably queued, leave "Preparing…" and show the
        // generic "Sending…" spinner. Don't move backwards from
        // Processing/Uploading/Completed if those somehow arrive first.
        viewModelScope.launch {
            eventBus.events.filter { it is BackendEvent.OutboxEvent.ItemEnqueued }
                .collect { event ->
                    event as BackendEvent.OutboxEvent.ItemEnqueued
                    _uiState.update { state ->
                        val current = state.uploadProgress[event.uniqueId]
                        if (current is UploadStatus.Preparing) {
                            state.copy(
                                uploadProgress = (state.uploadProgress + (event.uniqueId to UploadStatus.Sending)).toPersistentMap()
                            )
                        } else state
                    }
                }
        }

        viewModelScope.launch {
            eventBus.events.filter { it is BackendEvent.OutboxEvent.ItemCompleted }
                .collect { event ->
                    event as BackendEvent.OutboxEvent.ItemCompleted
                    viewModelScope.launch {
                        _uiState.update { state ->
                            state.copy(
                                uploadProgress = (state.uploadProgress + (event.uniqueId to UploadStatus.Completed)).toPersistentMap()
                            )
                        }
                        delay(800)
                        _uiState.update { state ->
                            state.copy(
                                uploadProgress = (state.uploadProgress - event.uniqueId).toPersistentMap()
                            )
                        }
                    }
                }
        }

        // ItemFailed = a single attempt failed; the outbox is expected to
        // retry. Surface a transient "Retrying…" state so the user knows the
        // moment hasn't actually shipped — clearing the overlay (chat's
        // behavior) makes the tile look indistinguishable from a successful
        // post until the next ItemProgress arrives, which on slow networks
        // can be tens of seconds. The next ItemProgress collector above
        // naturally overwrites this with Uploading on a retry.
        viewModelScope.launch {
            eventBus.events.filter { it is BackendEvent.OutboxEvent.ItemFailed }
                .collect { event ->
                    event as BackendEvent.OutboxEvent.ItemFailed
                    _uiState.update { state ->
                        state.copy(
                            uploadProgress = (state.uploadProgress + (event.uniqueId to UploadStatus.Failed(
                                permanent = false
                            ))).toPersistentMap()
                        )
                    }
                }
        }

        // OutboxItemDropped = the outbox gave up after max retries. Terminal:
        // no more auto-retry will happen this session. Surface a permanent
        // failure state — the user's optimistic-write copy still sits in the
        // feed (it's local-only at this point), but the overlay makes it
        // obvious delivery didn't happen.
        viewModelScope.launch {
            eventBus.events.filter { it is BackendEvent.OutboxEvent.OutboxItemDropped }
                .collect { event ->
                    event as BackendEvent.OutboxEvent.OutboxItemDropped
                    _uiState.update { state ->
                        state.copy(
                            uploadProgress = (state.uploadProgress + (event.uniqueId to UploadStatus.Failed(
                                permanent = true
                            ))).toPersistentMap()
                        )
                    }
                }
        }
    }
}
