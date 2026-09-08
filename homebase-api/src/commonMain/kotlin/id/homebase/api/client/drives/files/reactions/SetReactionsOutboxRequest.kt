package id.homebase.api.client.drives.files.reactions

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Declarative reaction state for one scope of a file (an event's RSVP, one
 * Groodle slot, a poll): every reaction in [remove] is deleted, then every one
 * in [add] is added. Both halves are idempotent, so a retried or replayed row
 * converges on the same server state instead of flipping like a toggle.
 */
@Serializable
data class SetReactionsOutboxRequest(
    val driveId: Uuid,
    val fileId: Uuid,
    val add: List<String>,
    val remove: List<String>,
    val recipients: List<OdinId>,
)
