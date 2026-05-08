package id.homebase.core.moments.services

import id.homebase.api.common.OdinId
import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/**
 * Opaque, per-emission identity for a recipient. Regenerated each time the
 * lookup service rebuilds the recipient list, so it must NOT be persisted by
 * callers. MRU bookkeeping uses an internal stable key managed by the lookup
 * service — see `MomentsRecipientLookupService.recordUsed`.
 */
@JvmInline
value class MomentsRecipientId(val raw: Uuid)

/**
 * A recipient that the moments composer can post to. Source-agnostic by
 * design: the lookup service aggregates from chat conversations and contacts
 * today and may grow more sources later, but consumers should not branch on
 * provenance.
 */
sealed interface MomentsRecipient {
    val id: MomentsRecipientId
    val displayName: String

    /** OdinIds the moment will be sent to. Always at least one. */
    val odinIds: List<OdinId>

    val avatarInitials: String
    val avatarUrl: String

    data class Individual(
        override val id: MomentsRecipientId,
        override val displayName: String,
        override val odinIds: List<OdinId>,
        override val avatarInitials: String,
        override val avatarUrl: String,
    ) : MomentsRecipient {
        val odinId: OdinId get() = odinIds.single()
    }

    data class Group(
        override val id: MomentsRecipientId,
        override val displayName: String,
        override val odinIds: List<OdinId>,
        override val avatarInitials: String,
        override val avatarUrl: String,
        val memberCount: Int,
        /** The underlying `MomentGroup.id` so callers can preserve group
         *  provenance on the moment's [MomentSource.Audience]. */
        val groupId: Uuid,
    ) : MomentsRecipient
}
