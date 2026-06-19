package id.homebase.core.ui.screens.devmenu

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.peer.temporal.TemporalDriveReadProvider
import id.homebase.api.common.OdinId
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.config.locationLabeledDrive
import id.homebase.core.notifications.RichNotificationData
import id.homebase.core.notifications.RichNotificationDisplayer
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.screens.location.model.LOCATION_POINTS_PAYLOAD_KEY
import id.homebase.core.ui.screens.location.model.LOCATION_TRACK_FILE_TYPE
import id.homebase.core.ui.screens.location.model.LocationTrackCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

class DeveloperMenuViewModel(
    private val driveSyncManager: DriveSyncManager,
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val userPreferences: UserPreferences,
    private val temporalDriveReadProvider: TemporalDriveReadProvider,
) : ViewModel() {

    companion object {
        private const val TEMPORAL_TAG = "TemporalRead"

        // Temporary hardcoded test target — the peer whose location drive we read. For a non-empty
        // result this identity must grant our logged-in identity ConditionalTemporalRead on the
        // location drive (via an emergency circle); `verify` works regardless.
        private const val TEST_PEER = "frodo.baggins.demo.rocks"
    }

    private val _uiState = MutableStateFlow(
        DeveloperMenuUiState(allowTenBitVideo = userPreferences.allowTenBitVideo)
    )
    val uiState: StateFlow<DeveloperMenuUiState> = _uiState.asStateFlow()

    fun onUiAction(action: DeveloperMenuUiAction) {
        when (action) {
            is DeveloperMenuUiAction.BackClicked -> {
                sendEvent(DeveloperMenuUiEvent.Back)
            }

            is DeveloperMenuUiAction.TestRichNotification -> {
                testRichNotification()
            }

            is DeveloperMenuUiAction.TestTemporalLocationRead -> {
                testTemporalLocationRead()
            }

            is DeveloperMenuUiAction.ForceSyncAll -> {
                forceSyncAll()
            }

            is DeveloperMenuUiAction.ClearAllData -> {
                clearAllData()
            }

            is DeveloperMenuUiAction.TriggerTestCrash -> {
                triggerTestCrash()
            }

            is DeveloperMenuUiAction.ToggleAllowTenBitVideo -> {
                val isEnabled = !userPreferences.allowTenBitVideo
                userPreferences.allowTenBitVideo = isEnabled
                _uiState.update { it.copy(allowTenBitVideo = isEnabled) }
            }

            is DeveloperMenuUiAction.ForceReconnectWebSocket -> {
                // Note: WebSocket client is managed by AuthConnectionCoordinator
                // We can trigger reconnection by calling disconnect, which will auto-reconnect
                sendEvent(DeveloperMenuUiEvent.Error(
                    "WebSocket reconnect: Use 'Force Sync All' to trigger reconnection logic"
                ))
            }
        }
    }

    private fun testRichNotification() {
        val richData = RichNotificationData(
            notificationId = 1,
            channelId = "messages",
            conversationId = null,
            title = "Test notification",
            body = "Body text of notification",
            senderName = "John Sender",
            senderId = "john.sender.homebase",
            senderImageBytes = null,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            payloadData = mapOf(),
            silent = false,
        )

        RichNotificationDisplayer().show(richData)
    }

    /**
     * Temporary dev probe for the new temporal (time-boxed / emergency) read API against
     * [TEST_PEER]'s location drive. Exercises verify → query-batch → header → payload, logging each
     * step under tag [TEMPORAL_TAG] and surfacing a snackbar. Safe to run without a grant: `verify`
     * reports `hasAccess=false` and the data calls return 404/403 (logged, not fatal).
     */
    private fun testTemporalLocationRead() {
        viewModelScope.launch {
            try {
                val peer = OdinId(TEST_PEER)
                val driveId = locationLabeledDrive.drive.alias
                Logger.i(tag = TEMPORAL_TAG) { "Temporal read test → peer=$TEST_PEER drive=$driveId" }

                val access = temporalDriveReadProvider.verifyTemporalAccess(peer, driveId)
                // newestFileModified is the "is data still flowing?" signal — 0 (ZeroTime) means
                // no files / no access, so only meaningful when hasAccess is true.
                val newestMs = access.newestFileModified.milliseconds
                Logger.i(tag = TEMPORAL_TAG) {
                    "verify → hasAccess=${access.hasAccess} windowSeconds=${access.windowSeconds} " +
                        "newestFileModified=${if (access.hasAccess && newestMs > 0) newestMs.toString() else "none"}"
                }

                val request = QueryBatchRequest(
                    queryParams = FileQueryParams(fileType = listOf(LOCATION_TRACK_FILE_TYPE)),
                    resultOptionsRequest = QueryBatchResultOptionsRequest(
                        maxRecords = 50,
                        includeMetadataHeader = true,
                        ordering = QueryBatchSortOrder.NewestFirst,
                    ),
                )
                val batch = temporalDriveReadProvider.temporalQueryBatch(peer, driveId, request)
                Logger.i(tag = TEMPORAL_TAG) { "query-batch → ${batch.searchResults.size} hour files" }
                for (file in batch.searchResults.take(5)) {
                    Logger.i(tag = TEMPORAL_TAG) { "  file=${file.fileId} created=${file.fileMetadata.created}" }
                }

                val newest = batch.searchResults.firstOrNull()
                if (newest != null) {
                    val header = temporalDriveReadProvider.temporalGetFileHeader(peer, driveId, newest.fileId)
                    val content = header?.fileMetadata?.appData?.content
                    val hour = content?.let { LocationTrackCodec.decodeHeader(it) }
                    Logger.i(tag = TEMPORAL_TAG) {
                        "header → file=${newest.fileId} points=${hour?.points?.size} full=${hour?.fullCount}"
                    }

                    val hasOverflow = header?.fileMetadata?.payloads
                        ?.any { it.keyEquals(LOCATION_POINTS_PAYLOAD_KEY) } == true
                    if (hasOverflow) {
                        val payload = temporalDriveReadProvider.temporalGetPayload(
                            peer, driveId, newest.fileId, LOCATION_POINTS_PAYLOAD_KEY,
                        )
                        Logger.i(tag = TEMPORAL_TAG) { "payload($LOCATION_POINTS_PAYLOAD_KEY) → ${payload?.bytes?.size} bytes" }
                    } else {
                        Logger.i(tag = TEMPORAL_TAG) { "payload → newest hour file has no '$LOCATION_POINTS_PAYLOAD_KEY' overflow" }
                    }
                }

                sendEvent(
                    DeveloperMenuUiEvent.Success(
                        "Temporal read OK — access=${access.hasAccess}, ${batch.searchResults.size} files (see log tag $TEMPORAL_TAG)"
                    )
                )
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TEMPORAL_TAG) { "Temporal read test failed" }
                sendEvent(DeveloperMenuUiEvent.Error("Temporal read failed: ${e.message}"))
            }
        }
    }

    private fun forceSyncAll() {
        viewModelScope.launch {
            try {
                Logger.i(tag = "DeveloperMenu") { "Force sync all triggered" }
                driveSyncManager.syncAll()
                sendEvent(DeveloperMenuUiEvent.Success("Sync completed successfully"))
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = "DeveloperMenu") { "Force sync failed" }
                sendEvent(DeveloperMenuUiEvent.Error("Sync failed: ${e.message}"))
            }
        }
    }

    private fun clearAllData() {
        viewModelScope.launch {
            try {
                Logger.i(tag = "DeveloperMenu") { "Clearing all data" }

                // Clear all sync data
                driveSyncManager.clearStorage()

                // Clear notifications (if user is logged in)
                val identityId = credentialsManager.getActiveCredentials()?.getIdentityId()
                if (identityId != null) {
                    databaseManager.appNotifications.deleteAll(identityId)
                }

                Logger.i(tag = "DeveloperMenu") { "All data cleared successfully" }
                sendEvent(DeveloperMenuUiEvent.Success("All data cleared. Please restart the app."))
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = "DeveloperMenu") { "Clear data failed" }
                sendEvent(DeveloperMenuUiEvent.Error("Failed to clear data: ${e.message}"))
            }
        }
    }

    /**
     * Intentionally crash the app to verify crash reporting end-to-end. Throws
     * an uncaught exception on the calling (main) thread so it propagates through
     * the platform crash handlers we install at startup — Firebase's native
     * uncaught handler on Android, and the Kotlin/Native unhandled-exception hook
     * on iOS ([id.homebase.core.logging.setupIOSCrashHandler], which records it to
     * Crashlytics). The report uploads on the next launch.
     */
    private fun triggerTestCrash() {
        Logger.w(tag = "DeveloperMenu") { "Triggering intentional test crash for Crashlytics verification" }
        throw RuntimeException("Test crash triggered from the developer menu (Crashlytics verification)")
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun sendEvent(event: DeveloperMenuUiEvent) {
        _uiState.update { it.copy(uiEvent = event) }
    }
}

@Immutable
data class DeveloperMenuUiState(
    val allowTenBitVideo: Boolean = false,
    val uiEvent: DeveloperMenuUiEvent? = null,
)

sealed interface DeveloperMenuUiEvent {
    data object Back : DeveloperMenuUiEvent
    data class Error(val errorMessage: String) : DeveloperMenuUiEvent
    data class Success(val message: String) : DeveloperMenuUiEvent
}

sealed interface DeveloperMenuUiAction {
    data object BackClicked : DeveloperMenuUiAction
    data object TestRichNotification : DeveloperMenuUiAction
    data object TestTemporalLocationRead : DeveloperMenuUiAction
    data object ForceSyncAll : DeveloperMenuUiAction
    data object ClearAllData : DeveloperMenuUiAction
    data object TriggerTestCrash : DeveloperMenuUiAction
    data object ForceReconnectWebSocket : DeveloperMenuUiAction
    data object ToggleAllowTenBitVideo : DeveloperMenuUiAction
}