package id.homebase.chat.services

import id.homebase.chat.data.MessageUiModel
import kotlin.uuid.Uuid

/**
 * Detects optimistic→server fileId transitions in an incoming sync batch.
 *
 * When an own message's send settles, the file syncs back with the SAME id
 * (uniqueId) but a SERVER-assigned fileId; the optimistic record (and the
 * payload-cache entries seeded under its client-minted fileId) are replaced.
 * This pairs each such incoming message with the old fileId still held by the
 * in-memory window so the caller can re-key the seeded cache entries before
 * the old id is lost.
 *
 * Candidate iff: the window holds a model for the incoming message's id, that
 * model is an own optimistic send (`isPendingSend`), the fileId actually
 * changed (edits/updates keep it → no-op), and the incoming file has payload
 * descriptors (text-only messages have nothing seeded worth moving).
 */
internal fun fileIdRekeyCandidates(
    windowMessages: List<MessageUiModel>,
    incoming: List<MessageUiModel>,
): List<Pair<Uuid, MessageUiModel>> = incoming.mapNotNull { msg ->
    val old = windowMessages.firstOrNull { it.id == msg.id } ?: return@mapNotNull null
    if (!old.isPendingSend) return@mapNotNull null
    if (old.fileId == msg.fileId) return@mapNotNull null
    if (msg.payloads.isNullOrEmpty()) return@mapNotNull null
    old.fileId to msg
}
