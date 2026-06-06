package id.homebase.chat.conversationsettings

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Aggregated overview of a conversation, surfaced on the
 * [ConversationSettingsScreen]. Counts and recent media are computed over the
 * most recent [ConversationSettingsViewModel.SUMMARY_MESSAGE_CAP] messages;
 * when [isTruncated] is true the conversation has more messages than the cap
 * and the counts reflect recent activity rather than the lifetime total.
 */
@Immutable
data class ChatSummaryUiModel(
    val totalMessages: Int,
    val isTruncated: Boolean,
    /** Earliest message timestamp — accurate even when [isTruncated]. */
    val firstMessageDate: Instant?,
    val photoCount: Int,
    val stickerCount: Int,
    val videoCount: Int,
    val audioCount: Int,
    val fileCount: Int,
    val linkCount: Int,
    val locationCount: Int,
    val diceRollCount: Int,
    val eventCount: Int,
    val pollCount: Int,
    /** Newest-first image attachments for the shared-media strip. */
    val recentMedia: ImmutableList<SharedMediaItem> = persistentListOf(),
) {
    val hasAnyStat: Boolean
        get() = photoCount > 0 || stickerCount > 0 || videoCount > 0 || audioCount > 0 ||
                fileCount > 0 || linkCount > 0 || locationCount > 0 || diceRollCount > 0 ||
                eventCount > 0 || pollCount > 0
}

/** One image attachment, carrying everything [id.homebase.chat.widget.MediaItem] needs to render it. */
@Immutable
data class SharedMediaItem(
    val fileId: Uuid,
    val payload: PayloadDescriptor,
    val keyHeader: KeyHeader,
    val previewThumbnail: EmbeddedThumb?,
    val isSticker: Boolean,
)
