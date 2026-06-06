package id.homebase.chat.conversationsettings

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.BatchResult
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.avatars.ConversationAvatarModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Everything shared in a conversation, bucketed by kind. Computed over the most
 * recent [ConversationSettingsViewModel.SUMMARY_MESSAGE_CAP] messages; when
 * [isTruncated] is true there are older messages beyond the scan window.
 *
 * [media] are images + videos (the visual grid); [files] documents; [audio]
 * voice/audio; [diceRolls] header-only dice messages; [locations] shared map
 * pins. All payload-backed kinds reuse [SharedMediaItem] so the same
 * [id.homebase.chat.widget.MediaItem] renderer draws them.
 */
@Immutable
data class ConversationOverview(
    val totalMessages: Int,
    val isTruncated: Boolean,
    val firstMessageDate: Instant?,
    val media: ImmutableList<SharedMediaItem> = persistentListOf(),
    val files: ImmutableList<SharedMediaItem> = persistentListOf(),
    val audio: ImmutableList<SharedMediaItem> = persistentListOf(),
    val diceRolls: ImmutableList<DiceRollItem> = persistentListOf(),
    val locations: ImmutableList<SharedMediaItem> = persistentListOf(),
)

/** One payload-backed attachment (image / video / file / audio / location). */
@Immutable
data class SharedMediaItem(
    val fileId: Uuid,
    val payload: PayloadDescriptor,
    val keyHeader: KeyHeader,
    val previewThumbnail: EmbeddedThumb?,
    val isSticker: Boolean,
    val date: Instant,
)

/** A header-only dice-roll message — no payload, just its summary label. */
@Immutable
data class DiceRollItem(
    val messageId: Uuid,
    val label: String,
    val date: Instant,
)

/** A group conversation that both the viewer and the contact belong to. */
@Immutable
data class GroupInCommonItem(
    val conversationId: Uuid,
    val name: String,
    val avatarModel: ConversationAvatarModel,
)

/**
 * Buckets a page of messages into the [ConversationOverview] kinds. Pure +
 * side-effect-free so it can run on [kotlinx.coroutines.Dispatchers.Default].
 * Mirrors MessageBubbleRaw's media filter (skips the internal default/descriptor
 * payloads) and keys location/link previews before the image branch (they carry
 * an image content-type too).
 */
fun collectConversationOverview(
    batch: BatchResult<MessageUiModel>,
    firstMessageDate: Instant?,
): ConversationOverview {
    val media = mutableListOf<SharedMediaItem>()
    val files = mutableListOf<SharedMediaItem>()
    val audio = mutableListOf<SharedMediaItem>()
    val locations = mutableListOf<SharedMediaItem>()
    val diceRolls = mutableListOf<DiceRollItem>()

    for (message in batch.records) {
        when (val content = message.messageContent) {
            is MessageContent.DiceRoll -> diceRolls.add(
                DiceRollItem(message.id, content.displayLabel, message.userDate)
            )
            else -> Unit
        }

        val payloads = message.payloads?.filter {
            it.key != ChatProtocol.DefaultPayloadKey &&
                    !it.key.startsWith(ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY)
        } ?: emptyList()

        for (payload in payloads) {
            fun item(isSticker: Boolean = false) = SharedMediaItem(
                fileId = message.fileId,
                payload = payload,
                keyHeader = message.keyHeader,
                previewThumbnail = message.previewThumbnail,
                isSticker = isSticker,
                date = message.userDate,
            )
            val contentType = payload.contentType
            when {
                payload.key == ChatProtocol.PAYLOAD_KEY_LINKS -> Unit // links not a tab
                payload.key == ChatProtocol.PAYLOAD_KEY_LOCATION -> locations.add(item())
                contentType == null -> Unit
                contentType.startsWith("image/") -> {
                    val isSticker = (payload.descriptorInfo() as? id.homebase.api.client.drives.files.DescriptorContent.ImageFile)?.isSticker == true
                    media.add(item(isSticker))
                }
                contentType.startsWith("video/") ||
                        contentType == "application/vnd.apple.mpegurl" -> media.add(item())
                contentType.startsWith("audio/") -> audio.add(item())
                else -> files.add(item())
            }
        }
    }

    return ConversationOverview(
        totalMessages = batch.records.size,
        isTruncated = batch.hasMoreRows,
        firstMessageDate = firstMessageDate,
        media = media.toImmutableList(),
        files = files.toImmutableList(),
        audio = audio.toImmutableList(),
        diceRolls = diceRolls.toImmutableList(),
        locations = locations.toImmutableList(),
    )
}
