package id.homebase.core.ui.screens.defragmenter.model

/**
 * Per-cell state ordinals used by the canvas to colour each filled block.
 *
 * Stored as a `ByteArray` of length [DefragmenterUiState.totalBlocks] so the
 * canvas can look up a state by index in O(1) without a Map. `SoftDeleted` is
 * NOT encoded here — soft-deletes are represented as gaps via
 * [BlockGrid.isFilled] returning false, so the canvas already skips drawing
 * them. The classifier cell types collapse to these codes:
 *
 *  - Healthy                    → [CELL_HEALTHY]
 *  - LegacyUserDateZero         → [CELL_LEGACY_USERDATE_ZERO]
 *  - SoftDeleteArchivalMismatch → [CELL_SOFT_DELETE_ARCHIVAL_MISMATCH]
 *  - CorruptJsonHeader          → [CELL_CORRUPT_JSON_HEADER]
 *  - UnmappableConversation     → [CELL_UNMAPPABLE_CONVERSATION]
 *  - OrphanChatMessage          → [CELL_ORPHAN_CHAT_MESSAGE]
 */
const val CELL_HEALTHY: Byte = 0
const val CELL_LEGACY_USERDATE_ZERO: Byte = 1
const val CELL_SOFT_DELETE_ARCHIVAL_MISMATCH: Byte = 2
const val CELL_CORRUPT_JSON_HEADER: Byte = 3
const val CELL_UNMAPPABLE_CONVERSATION: Byte = 4
const val CELL_ORPHAN_CHAT_MESSAGE: Byte = 5
