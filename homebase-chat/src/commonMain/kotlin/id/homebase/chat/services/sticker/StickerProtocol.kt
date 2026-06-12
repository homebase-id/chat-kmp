@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.services.sticker

import id.homebase.api.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Wire constants + tiny appData content shape for saved stickers.
 *
 * A saved sticker is one Standard HomebaseFile on the dedicated Stickers drive
 * ([id.homebase.core.config.stickerLabeledDrive]), `fileType = STICKER_FILE_TYPE`,
 * carrying a single transparent image payload (+ thumbnail). The per-payload
 * sticker marker rides on the payload's `descriptorContent` as
 * `{"isSticker":true}` — the SAME wire format PR #664 introduced — so the bare
 * sticker render path is reused end-to-end and nothing new is added to the
 * message protocol.
 *
 * [StickerFileContent] is deliberately tiny (optional name only). Per CLAUDE.md,
 * identity/author/timestamp live on the HomebaseFile envelope and must NOT be
 * duplicated into the descriptor — that keeps us well under the 7 KB header budget.
 * The future "packs" grouping is reserved on [SavedSticker.groupId]
 * (the envelope's appData.groupId), not here, so it needs no migration.
 */
object StickerProtocol {
    /**
     * fileType for a saved sticker file. 7060 sits just past the Moments 705x block
     * (7050-7054) and is distinct from Vault (5572/5573) and chat (7878/8888/8889),
     * so a Stickers-drive QueryBatch can filter on it unambiguously.
     */
    const val STICKER_FILE_TYPE = 7060

    /**
     * Payload key for the solo sticker image payload. MUST match the server's payload-key
     * pattern `^[a-z0-9_]{8,10}$` (8–10 chars) — the same rule [id.homebase.chat.services.ChatProtocol.DefaultPayloadKey]
     * ("dflt_key") satisfies. The original "stk_00" (6 chars) was rejected by the drive
     * thumb/payload endpoints with "Missing payload key", which broke sticker thumbnails.
     */
    const val STICKER_PAYLOAD_KEY = "sticker_0"
}

/**
 * Tiny appData content for a saved sticker.
 *
 * [name] is an optional label (unused by the v1 tray). [sourceFileId] records the
 * chat-message file a sticker was saved FROM, so the sticker-tap bottom sheet can ask
 * "is this received sticker already in my library?" — a saved copy is re-uploaded with a
 * fresh random uniqueId and shares no identity with the source message, so without this
 * back-reference there is no way to detect a duplicate. Null when the sticker was created
 * by the in-app editor / background-remover (no originating message). Per CLAUDE.md this is
 * NOT a duplicated envelope field: it points at a *different* file (the source message),
 * not this sticker's own identity.
 */
@Serializable
data class StickerFileContent(
    val name: String? = null,
    @Serializable(with = UuidSerializer::class) val sourceFileId: Uuid? = null,
)
