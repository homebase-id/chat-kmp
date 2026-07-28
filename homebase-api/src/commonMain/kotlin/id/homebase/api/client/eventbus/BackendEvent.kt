package id.homebase.api.client.eventbus

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.websockets.CircleDefinitionChangeType
import id.homebase.api.client.websockets.ConnectionChangeType
import id.homebase.api.client.websockets.Introduction
import id.homebase.api.client.websockets.PublicProfileArtifact
import id.homebase.api.common.OdinId
import id.homebase.api.video.VideoProcessingPhase
import kotlin.uuid.Uuid

sealed interface BackendEvent {
    /**
     * Termination reason for a DriveSync round. Independent of `totalCount` —
     * `Aborted` and `PermissionDenied` may still carry `totalCount > 0` because
     * earlier batches' DB writes commit before any later batch can fail.
     * Consumers that just want "did any data land in DriveMainIndex this round"
     * should gate on `totalCount > 0` alone, NOT on result == Completed.
     */
    sealed interface DriveResult {
        /** Sync walked the cursor all the way to HEAD without error. */
        data object Completed : DriveResult
        /**
         * Sync stopped early because of a DB write error or a network error
         * that exceeded retries. Some earlier batches may already have landed
         * (see `Stopped.totalCount`).
         */
        data class Aborted(
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

        /**
         * An existing connection's state changed elsewhere (disconnect/block/unblock) or a circle
         * was granted/revoked to it. Pushed to all the owner's sessions for any origin — including
         * an echo of this device's own mutation, so consumers must be idempotent. [circleId] is
         * non-null only for [ConnectionChangeType.CircleGranted] / [ConnectionChangeType.CircleRevoked].
         */
        data class ConnectionChanged(
            val identity: String,
            val change: ConnectionChangeType,
            val circleId: String?,
        ) : CircleNetworkEvent

        /** A circle definition itself changed elsewhere (not its membership). Echoes like above. */
        data class CircleDefinitionChanged(
            val circleId: String,
            val change: CircleDefinitionChangeType,
        ) : CircleNetworkEvent

    }

    /**
     * Sync-state-machine signals from [DriveSync.performSync]: drive started a
     * sync round, made progress through a batch upsert, finished (success or
     * failure). Consumed by [DriveSyncManager] to drive `DriveStatus` /
     * `SyncState` / the login-screen progress UI. Carry no file data — that
     * lives in [DataEvent].
     */
    sealed interface DriveEvent : BackendEvent {
        val driveId: Uuid

        data class Started(
            override val driveId: Uuid,
        ) : DriveEvent

        /**
         * Per-batch progress signal during [DriveSync.performSync]. [totalCount]
         * is the running total of records upserted in this sync round (not the
         * batch size). [DriveSyncManager] uses it to advance
         * `Synchronizing(count = totalCount)` so the login-screen
         * `DriveProgressRow` shows "N records" while sync is running.
         */
        data class Progress(
            override val driveId: Uuid,
            val totalCount: Int,
        ) : DriveEvent

        data class Stopped(
            override val driveId: Uuid,
            val totalCount: Int,  // records fetched so far — always present (0 if failed immediately)
            val result: DriveResult
        ) : DriveEvent
    }

    /**
     * Data-arrival signals: file headers have been written to `DriveMainIndex`
     * and are ready for consumers to react to. Emitted by:
     *  - [DriveWebSocketUpsertWorker]: per drained batch of WS-pushed file headers
     *  - [OptimisticWriter]: per in-process optimistic write
     * NOT emitted by [DriveSync.performSync] (silent-DriveSync contract —
     * consumers reload from local DB on [DriveEvent.Stopped] with totalCount > 0).
     */
    sealed interface DataEvent : BackendEvent {
        val driveId: Uuid

        data class BatchReceived(
            override val driveId: Uuid,
            val batchData: List<HomebaseFile>,
        ) : DataEvent
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
            val bytesSent: Long? = null,
            // True when the outbox row is a file *create*, which carries the item's
            // payloads. False for a header-only update (an edit): the bytes moving
            // are metadata, not media, so a media-progress UI must not react to it
            // (#1155).
            val isCreate: Boolean = true
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

        /** Fired when an item is permanently dropped — either a permanent
         *  (never-retryable) failure or the max retry limit was exceeded.
         *  [reason] is human-readable diagnostics (classifier reason or
         *  "retries exhausted (N)"), for logs/Message Info — not for branching. */
        data class OutboxItemDropped(
            val driveId: Uuid,
            val uniqueId: Uuid,
            val attempts: Int,
            val reason: String? = null,
        ) : OutboxEvent

    }
    // Add sealed interface UploadUpdate for Outbox / upload status
    // Add sealed interface VideoUpdate (or WorkUpdate) compression & segmentation & encryption

    sealed interface PayloadBundlingEvent : BackendEvent {

        /**
         * Emitted right after the sender has written a placeholder row to the
         * local DB but before thumbnail generation / encryption start. Lets
         * the UI show an indeterminate "Preparing…" overlay on the new tile
         * until a later event (video phase progress, ItemEnqueued, etc.)
         * supersedes it.
         */
        data class Preparing(
            val uniqueId: Uuid,
        ) : PayloadBundlingEvent

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

    /**
     * Emitted once when the user logs out (authState → Unauthenticated), AFTER the
     * WebSocket and DriveSync have been torn down so no producer can re-populate
     * afterwards. Stateful singletons (ConversationStream, ChatMessageStream, the
     * contact/connection/moments/vault services, …) listen for this and clear their
     * own in-memory per-identity caches, mirroring the SQL DB wipe in
     * [id.homebase.api.youauth.YouAuthFlowManager.logout]. Decentralised by design:
     * each service owns its teardown instead of logout() reaching into all of them.
     */
    data object SessionEnded : BackendEvent

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

    /**
     * Emitted when the user returns from the owner-console data-upgrade page
     * (via deep link on mobile or /data-upgrade-callback on desktop).
     * PendingUpgradeManager listens for this and immediately re-checks upgrade
     * status so the UI clears or updates without waiting for the next poll.
     */
    data object DataUpgradeReturned : BackendEvent

    // We need an event for when someone is typing something for you...
    // data object UserTyping : backendEvent

    /**
     * An ephemeral Live Relay blob arrived over the notification websocket (e.g. a live GPS point
     * from a connected identity). Last-value-wins, nothing durable — if you're offline the data is
     * just gone. [senderOdinId] is authoritative; [receivedAt] is the server-received time (ms) for
     * staleness; [blob] is the opaque base64 payload; [channelKey] routes to the right open session.
     */
    data class LiveRelayReceived(
        val senderOdinId: OdinId,
        val channelKey: String,
        val blob: String,
        val receivedAt: Long,
    ) : BackendEvent

    /**
     * One of the owner's derived public-profile artifacts (`sitedata.json`, `/pub/image`,
     * `/pub/profile`) was republished server-side — fired after ANY profile-attribute write, not
     * just photo uploads. No diff payload; consumers should re-fetch the named [artifact]. Always
     * about the logged-in owner's own identity (broadcast to every session for the tenant, never
     * about a peer). See [id.homebase.api.client.auth.OwnerSessionRepository] for the primary
     * consumer.
     */
    data class PublicProfileContentPublished(
        val artifact: PublicProfileArtifact,
    ) : BackendEvent
}
