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

        data object SyncAllStarted: BackendEvent

        data object SyncAllCompleted: BackendEvent

        data class Started(
            override val driveId: Uuid,
        ) : DriveEvent // Only raised by Drive.sync()

        data class Completed(
            override val driveId: Uuid,
            val totalCount: Int
        ) : DriveEvent  // Only raised by Drive.sync() when a drive is fully synced

        data class Failed(
            override val driveId: Uuid,
            val errorMessage: String,  // Or add throwable: Throwable
            val source: SyncSource = SyncSource.DriveSync
        ) : DriveEvent

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

    // We go online / offline when the websocket listener is connected / disconnected
    data object Connecting : BackendEvent
    data object ConnectionOnline : BackendEvent
    data object ConnectionOffline : BackendEvent

    // We need an event for when someone is typing something for you...
    // data object UserTyping : backendEvent
}
