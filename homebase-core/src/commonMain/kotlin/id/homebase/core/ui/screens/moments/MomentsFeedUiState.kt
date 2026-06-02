package id.homebase.core.ui.screens.moments

import androidx.compose.runtime.Immutable
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.video.VideoProcessingPhase
import id.homebase.core.avatars.AppConnectionStatus
import id.homebase.core.moments.MomentsAlbumZoom
import id.homebase.core.moments.MomentsViewMode
import id.homebase.core.moments.services.MomentFeedItem
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlin.uuid.Uuid

@Immutable
data class MomentsFeedUiState(
    val moments: List<MomentFeedItem> = emptyList(),
    val ownerSession: OwnerSession? = null,
    val connectionStatus: AppConnectionStatus = AppConnectionStatus.Connecting,
    val driveIsSyncing: Boolean = false,
    val hasDriveError: Boolean = false,
    val uploadProgress: ImmutableMap<Uuid, UploadStatus> = persistentMapOf(),
    /**
     * Transient map of (moment uniqueId → local file URI) for moments whose
     * placeholder row has been written but whose real thumbnails/payloads are
     * still being generated. Lets the feed tile render the user's source image
     * directly during the "Preparing…" window — without it, the tile would be
     * a description-only fallback until thumbnails land.
     *
     * The value is a Coil model: a local file-path [String] for photos, or the
     * extracted poster-frame JPEG [ByteArray] for videos. (A raw video path
     * can't be decoded by an image loader, so videos must hand over their
     * poster bytes — otherwise the tile renders black for the whole window.)
     *
     * Populated by [id.homebase.core.moments.services.MomentsPostSenderService]
     * and cleared once the real optimistic write installs the embedded preview.
     */
    val pendingLocalPreviews: ImmutableMap<Uuid, Any> = persistentMapOf(),
    val viewMode: MomentsViewMode = MomentsViewMode.Timeline,
    val albumZoom: MomentsAlbumZoom = MomentsAlbumZoom.Day,
)

/**
 * Moments-local clone of `id.homebase.chat.conversationlist.UploadStatus`. Kept
 * separate so the moments feed can evolve its upload UX (e.g. a persistent
 * Failed/retry state on a stuck tile) without coupling to the chat send
 * pipeline. The shared contract that actually matters is the `BackendEvent`
 * outbox/bundling events that drive both — those live in `homebase-api`.
 */
sealed interface UploadStatus {
    data object Preparing : UploadStatus
    data class Processing(
        val progress: Float,
        val phase: VideoProcessingPhase = VideoProcessingPhase.COMPRESSING,
    ) : UploadStatus
    data object Sending : UploadStatus
    data class Uploading(val progress: Float) : UploadStatus
    data object Completed : UploadStatus

    /**
     * Upload didn't reach the server. [permanent] = false means the outbox
     * fired `ItemFailed` on a single attempt and is expected to retry — the
     * next `ItemProgress` will overwrite this state. [permanent] = true means
     * `OutboxItemDropped` fired (max retries exhausted) and no further auto-
     * retry will happen; the tile will stay in this state for the rest of the
     * session unless something else moves it. There is no manual retry path
     * yet — that's a follow-up.
     */
    data class Failed(val permanent: Boolean) : UploadStatus
}
