package id.homebase.core.contactbook

import id.homebase.api.client.connections.CircleDesignation
import id.homebase.api.client.connections.CircleGrantOn
import id.homebase.api.client.connections.ConnectionRequestOrigin
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.api.common.OdinId
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.ui.screens.contactbook.ContactState
import id.homebase.core.ui.screens.contactbook.contactStateOf
import id.homebase.core.ui.screens.contactbook.isPersonalCircle
import id.homebase.core.ui.screens.contactbook.isUserCircle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactStateTest {

    private fun connection(
        reviewedAt: Long? = null,
        vetted: Boolean = false,
        status: ConnectionStatus = ConnectionStatus.Connected,
    ) = RedactedIdentityConnectionRegistration(
        odinId = OdinId("sam.dotyou.cloud"),
        status = status,
        created = 1,
        lastUpdated = 2,
        connectionRequestOrigin = ConnectionRequestOrigin.IdentityOwner,
        hasVerificationHash = false,
        rku = false,
        reviewedAt = reviewedAt,
        vetted = vetted,
    )

    private fun circle(
        id: String = "aa",
        grantOn: CircleGrantOn = CircleGrantOn.None,
        designation: CircleDesignation = CircleDesignation.Personal,
    ) = RedactedCircleDefinition(id = id, name = "c", grantOn = grantOn, designation = designation)

    @Test
    fun anUnreviewedConnectionIsNew() {
        assertEquals(ContactState.New, contactStateOf(connection(), emptyList()))
    }

    @Test
    fun reviewedWithNothingGrantedIsChat() {
        assertEquals(ContactState.Chat, contactStateOf(connection(reviewedAt = 99), emptyList()))
    }

    @Test
    fun aPersonalCircleMakesItCircle() {
        assertEquals(
            ContactState.Circle,
            contactStateOf(connection(reviewedAt = 99), listOf(circle())),
        )
    }

    /** Membership granted from another surface is evidence of review whatever the stamp says. */
    @Test
    fun circleMembershipOutranksAMissingStamp() {
        assertEquals(ContactState.Circle, contactStateOf(connection(), listOf(circle())))
    }

    /** A server below the review rollout sends vetted alone. */
    @Test
    fun theVettedAliasStillReadsAsReviewed() {
        assertEquals(ContactState.Chat, contactStateOf(connection(vetted = true), emptyList()))
    }

    @Test
    fun anIdentityThatIsNotAConnectionHasNoState() {
        assertNull(contactStateOf(null, emptyList()))
        assertNull(contactStateOf(connection(status = ConnectionStatus.Blocked), emptyList()))
    }

    /**
     * Ambient enrolment happens with no owner present, so it cannot be the evidence that the owner
     * vouched for someone — the whole failure mode this ladder replaces.
     */
    @Test
    fun ambientCirclesDoNotCountTowardCircleState() {
        assertFalse(circle(grantOn = CircleGrantOn.Connect).isPersonalCircle())
        assertFalse(circle(grantOn = CircleGrantOn.OwnFlowConnect).isPersonalCircle())
        assertFalse(circle(id = AUTO_CONNECTIONS_CIRCLE_ID).isPersonalCircle())
    }

    /** A review circle is granted by the owner's own review, which is exactly what ⭕ reports. */
    @Test
    fun aReviewCircleCountsTowardCircleStateButIsNotHandAssignable() {
        val moments = circle(grantOn = CircleGrantOn.Review)

        assertTrue(moments.isPersonalCircle())
        assertFalse(moments.isUserCircle())
    }

    @Test
    fun audienceAndVendorCirclesNeverCount() {
        assertFalse(circle(designation = CircleDesignation.Audience).isPersonalCircle())
        assertFalse(circle(designation = CircleDesignation.Vendor).isPersonalCircle())
    }
}
