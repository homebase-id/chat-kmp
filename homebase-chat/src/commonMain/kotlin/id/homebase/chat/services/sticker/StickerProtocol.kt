package id.homebase.chat.services.sticker

import kotlinx.serialization.Serializable

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

    /** Payload key for the solo sticker image payload. */
    const val STICKER_PAYLOAD_KEY = "stk_00"
}

@Serializable
data class StickerFileContent(
    val name: String? = null,
)
