package id.homebase.api.client.eventbus

import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.websockets.Introduction
import id.homebase.api.common.OdinId
import id.homebase.api.video.VideoProcessingPhase
import kotlin.uuid.Uuid

sealed interface BackendEvent {
    enum class SyncSource {
        DriveSync,
        WebSocket
    }

    sealed interface DriveResult {
        data object Success : DriveResult
        data class Failure(
            val errorMessage: String
        ) : DriveResult
        /** Server returned 403 Forbidden — drive is unmounted for this session; no retry. */
        data object PermissionDenied : DriveResult
    }

    sealed interface SyncAllResult {
        data object Success : SyncAllResult
        data object Failure : SyncAllResult
    }

    sealed interface CircleNetworkEvent : BackendEvent {

        data class ConnectionRequestReceived(val sender: OdinId) : CircleNetworkEvent

        data class ConnectionRequestAccepted(val acceptedBy: OdinId) : CircleNetworkEvent
        data class ConnectionRequestFinalized(val identity: OdinId) : CircleNetworkEvent
        data class NewFollower(val identity: OdinId) : CircleNetworkEvent

        data class IntroductionAccepted(
            val introducerOdinId: OdinId,
            val recipient: OdinId
        ) : CircleNetworkEvent


        data class IntroductionsReceived(
            val introducerOdinId: OdinId,
            val introduction: Introduction
        ) : CircleNetworkEvent

    }

    // A DriveEvent event happens on a drive when either sync() has received a batch of data
    // from the host, or when the websocket listener has received some data.
    sealed interface DriveEvent : BackendEvent {
        val driveId: Uuid  // Common property for all sync events (implement in each data class)

        data class Started(
            override val driveId: Uuid,
        ) : DriveEvent // Only raised by Drive.sync()

        data class Stopped(
            override val driveId: Uuid,
            val totalCount: Int,  // records fetched so far — always present (0 if failed immediately)
            val result: DriveResult
        ) : DriveEvent  // Only raised by Drive.sync() when a drive finishes (success or failure)

        data class BatchReceived(
            override val driveId: Uuid,
            val totalCount: Int,
            val batchCount: Int,
            val latestModified: UnixTimeUtc?,
            val batchData: List<HomebaseFile>,
            val source: SyncSource = SyncSource.DriveSync
        ) : DriveEvent
    }


    sealed interface OutboxEvent : BackendEvent {
        data object Started : OutboxEvent

        data class Completed(
            val totalCount: Int
        ) : OutboxEvent  // Only raised by Drive.sync()

        data class Failed(
            val errorMessage: String?  // Or add throwable: Throwable
        ) : OutboxEvent

        data class ItemEnqueued(
            val driveId: Uuid,
            val uniqueId: Uuid
        ) : OutboxEvent
        // When beginning to send an item we guarantee itemStarted event (0%)
        data class ItemStarted(
            val driveId: Uuid,
            val fileId: Uuid,
            val totalBytes: Long? = null
        ) : OutboxEvent  // Only raised by Drive.sync()

        // Progress during the sending of an item ]0..100[ %
        data class ItemProgress(
            val driveId: Uuid,
            val uniqueId: Uuid,
            val progress: Float,  // 0.0 to 1.0
            val bytesSent: Long? = null
        ) : OutboxEvent  // New: For ongoing upload progress updates

        data class ItemFailed(
            val driveId: Uuid,
            val uniqueId: Uuid
        ) : OutboxEvent

        // When the item has been delivered we guarantee itemCompleted event (100%)
        data class ItemCompleted(
            val driveId: Uuid,
            val uniqueId: Uuid
        ) : OutboxEvent  // Only raised by Drive.sync()

        /** Fired when an optimistic write is rolled back because the message never reached
         *  the outbox (e.g. tryEnqueue failed). Distinct from a deleted file. */
        data class OptimisticRollback(
            val driveId: Uuid,
            val uniqueId: Uuid,
        ) : OutboxEvent

        /** Fired when an item is permanently dropped after exceeding the max retry limit. */
        data class OutboxItemDropped(
            val driveId: Uuid,
            val uniqueId: Uuid,
            val attempts: Int
        ) : OutboxEvent

    }
    // Add sealed interface UploadUpdate for Outbox / upload status
    // Add sealed interface VideoUpdate (or WorkUpdate) compression & segmentation & encryption

    sealed interface PayloadBundlingEvent : BackendEvent {

        /* ---------- VIDEO ---------- */

        sealed interface Video : PayloadBundlingEvent {

            data class Started(
                val payloadKey: String
            ) : Video

            data class PhaseStarted(
                val payloadKey: String,
                val phase: VideoProcessingPhase
            ) : Video

            data class PhaseProgress(
                val uniqueId: Uuid,
                val payloadKey: String,
                val phase: VideoProcessingPhase,
                val progress: Float // 0.0 → 1.0
            ) : Video

            data class PhaseCompleted(
                val payloadKey: String,
                val phase: VideoProcessingPhase
            ) : Video

            data class Completed(
                val payloadKey: String
            ) : Video

            data class Failed(
                val payloadKey: String,
                val errorMessage: String
            ) : Video
        }
    }

    // Emitted when a full sync-all-drives operation starts/finishes
    data object SyncAllStarted : BackendEvent
    data class SyncAllStopped(val result: SyncAllResult) : BackendEvent

    // We go online / offline when the websocket listener is connected / disconnected
    data object Connecting : BackendEvent
    data object ConnectionOnline : BackendEvent
    data object ConnectionOffline : BackendEvent

    // A drive subscription was rejected by the server (non-fatal — other drives still sync)
    data class DriveAuthorizationFailed(val message: String) : BackendEvent

    /**
     * Emitted when the user has returned from the owner-console "Extend Permissions" flow.
     * On desktop this fires when the local callback server hits its /permission-callback
     * endpoint. ExtendPermissionViewModel listens for this and re-runs its check so the
     * dialog dismisses (or re-shows) immediately rather than waiting for the next event.
     */
    data object PermissionsExtensionReturned : BackendEvent

    /**
     * Emitted when the user explicitly canceled the owner-console "Extend Permissions"
     * flow (the redirect URL carried `status=canceled`). Distinct from
     * [PermissionsExtensionReturned] so the VM can route the user back to the chat tab
     * without re-prompting with the same dialog they just dismissed.
     */
    data object PermissionsExtensionCanceled : BackendEvent

    // We need an event for when someone is typing something for you...
    // data object UserTyping : backendEvent
}
