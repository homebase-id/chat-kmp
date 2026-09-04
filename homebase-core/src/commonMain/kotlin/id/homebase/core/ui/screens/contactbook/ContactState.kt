package id.homebase.core.ui.screens.contactbook

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.CircleDesignation
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration

/**
 * The three contact states. Working names from the proposal's option A; option B renames these
 * to New / Known / Trusted without changing the definitions.
 */
enum class ContactState { New, Chat, Circle }

@Immutable
data class ContactStateInfo(
    val state: ContactState,
    /** Owner-granted personal circles only — the ones rendered as pills on the row. */
    val circles: List<RedactedCircleDefinition> = emptyList(),
    /** Chosen but not yet in effect: the owning app hasn't drained its enrollment queue. */
    val pendingCircles: List<RedactedCircleDefinition> = emptyList(),
)

/**
 * Classifies connections against a snapshot of circle definitions.
 *
 * The trap this exists to contain: a circle's `designation` alone does *not* mean the owner
 * chose it. Chat's auto-connect circle is designated PERSONAL, and every connection is in it —
 * so classifying on designation puts everyone in the Circle state. Only circles that are also
 * `grantOn == none` count, which is the same predicate the server enforces on un-review (3012).
 */
class ContactStates(circles: List<CircleWithMembers>) {

    private val ownerPersonalByDomain: Map<String, List<RedactedCircleDefinition>> =
        circles.filter { it.circle.countsAsOwnerCircle() }
            .flatMap { cwm -> cwm.members.map { it.domainName.lowercase() to cwm.circle } }
            .groupBy({ it.first }, { it.second })

    private val audienceDomains: Set<String> =
        circles.filter { it.circle.designation == CircleDesignation.Audience }
            .flatMap { cwm -> cwm.members.map { it.domainName.lowercase() } }
            .toSet()

    // Circle ids arrive hyphenated on some shapes and as 32-char "N"-format on others; compare
    // on a normalized form so a pending enrollment still resolves to its definition.
    private val circlesById: Map<String, RedactedCircleDefinition> =
        circles.associateBy({ normalizeCircleId(it.circle.id) }, { it.circle })

    init {
        // ReviewDiag: temporary.
        Logger.d {
            "ReviewDiag/predicate ownerPersonalCircles=" +
                circles.filter { it.circle.countsAsOwnerCircle() }.map { it.circle.name } +
                " rejected=" + circles.filterNot { it.circle.countsAsOwnerCircle() }
                    .map {
                        "${it.circle.name}(grantOn=${it.circle.grantOn}," +
                            "designation=${it.circle.designation},system=${isSystemCircle(it.circle.id)})"
                    }
        }
    }

    fun infoFor(reg: RedactedIdentityConnectionRegistration): ContactStateInfo {
        val domain = reg.odinId.domainName.lowercase()
        val held = ownerPersonalByDomain[domain].orEmpty()
        // Two distinct "not yet in effect" buckets, indistinguishable to the user: circles this
        // app deposited but couldn't mint (no master key — the common case), and circles queued
        // for another app to enrol.
        val pending = (reg.pendingCircleEnrollments + reg.accessGrant?.pendingCircleIds.orEmpty())
            .mapNotNull { circlesById[normalizeCircleId(it.toString())] }
            .distinctBy { it.id }

        // Membership in a circle the owner deliberately granted is itself evidence of review —
        // it survives a server that never stamped, and it is why removing someone's last circle
        // lands them in Chat rather than back in New.
        val state = when {
            held.isNotEmpty() -> ContactState.Circle
            reg.isReviewed -> ContactState.Chat
            else -> ContactState.New
        }
        // ReviewDiag: temporary — remove once the New/Chat/Circle derivation is confirmed
        // against a live tenant.
        Logger.d {
            "ReviewDiag/state $domain -> $state (reviewedAt=${reg.reviewedAt} vetted=${reg.vetted} " +
                "isReviewed=${reg.isReviewed} ownerCircles=${held.map { it.name }} " +
                "audience=${domain in audienceDomains} pending=${pending.map { it.name }})"
        }
        return ContactStateInfo(state, held, pending)
    }

    fun stateFor(reg: RedactedIdentityConnectionRegistration): ContactState = infoFor(reg).state

    /**
     * Subscribers are approved in the app that owns their audience circle, which never stamps
     * `reviewedAt` — without this they would all read as New and a popular feed would show a
     * five-figure review badge.
     */
    fun isAudienceMember(domain: String): Boolean = domain.lowercase() in audienceDomains

    fun personalCirclesFor(domain: String): List<RedactedCircleDefinition> =
        ownerPersonalByDomain[domain.lowercase()].orEmpty()

    private companion object {
        fun normalizeCircleId(id: String): String = id.replace("-", "").lowercase()
    }
}

/**
 * The two legacy system circles come back indistinguishable from a user circle —
 * `grantOn=None designation=Personal appId=null` — because the server has not stamped them with
 * a GrantOn. Without this exclusion every auto-connected identity is a member of
 * `Auto-connected Identities` and therefore reads as Circle, never New.
 *
 * Delete along with the hardcoded ids once those circles dissolve into per-app defaults, or
 * sooner if the server marks them `grantOn=Connect`.
 */
fun RedactedCircleDefinition.countsAsOwnerCircle(): Boolean =
    isOwnerGrantedPersonal && !isSystemCircle(id)
