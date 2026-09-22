package id.homebase.api.client.websockets

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable

/**
 * An enrollment is queued for a circle this app owns: the owner ticked it while reviewing
 * [odinId] in some other app's client, which cannot mint the grant because it holds none of this
 * app's drive keys.
 *
 * Delivered only to sockets belonging to the owning app, so receiving one means it is ours to
 * finish. Carries enough to log which contact and circle; the work itself is claimed by sending
 * `processEnrollments`, which drains the whole queue rather than this one entry.
 */
@Serializable
data class PendingEnrollmentNotification(
    val odinId: OdinId? = null,
    val circleId: String? = null,
)
