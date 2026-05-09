package id.homebase.chat.services

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
class StatusMessageData(
    val statusMessage: StatusMessage,
    val subject: OdinId? = null,

    /** Set on [StatusMessage.GroupHealRequested]. Carries the canonical group
     *  identity so recipients can detect a divergent local copy and clean up. */
    val groupHeal: GroupHealInfo? = null,

    /** Set on [StatusMessage.GroupHealLocalCleanup] (local-only). Records which
     *  broken local files the receive-side heal handler hard-deleted. */
    val groupHealCleanup: GroupHealCleanupInfo? = null,
)

@Serializable
data class GroupHealInfo(
    val conversationUniqueId: Uuid,
    val canonicalOriginalAuthor: OdinId,
    val canonicalVersionTag: Uuid?,
    val canonicalTitle: String,
    val canonicalParticipants: List<OdinId>,
    val canonicalAdminFileUniqueId: Uuid,
    val canonicalAdminVersionTag: Uuid?,
    // Nullable for backwards-compat: pre-hardening senders never set this. When
    // present, the receive-side handler can rebuild a fully-formed local admin
    // placeholder (matching content) instead of just deleting the broken row
    // and relying on getAdmins()' originalAuthor fallback.
    val canonicalAdmins: List<OdinId>? = null,
)

@Serializable
data class GroupHealCleanupInfo(
    val cleanedUpMain: Boolean,
    val cleanedUpAdmin: Boolean,
)
