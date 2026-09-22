package id.homebase.core.contactbook

import id.homebase.api.client.connections.CircleDesignation
import id.homebase.api.client.connections.CircleGrantOn
import id.homebase.api.client.connections.ConnectionRequestOrigin
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.api.common.OdinId
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.ui.screens.contactbook.blocksUnreview
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.core.ui.screens.contactbook.ContactState
import id.homebase.core.ui.screens.contactbook.contactStateOf
import id.homebase.core.ui.screens.contactbook.isPersonalCircle
import id.homebase.core.ui.screens.contactbook.isAmbientCircle
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

    /**
     * `vetted` is the retired alias and nothing reads it any more: a contact carrying it without
     * a stamp is New, because the stamp is the only record of a review.
     */
    @Test
    fun theVettedAliasIsIgnored() {
        assertEquals(ContactState.New, contactStateOf(connection(vetted = true), emptyList()))
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

    /**
     * A review circle is granted by the owner's own review, which is exactly what ⭕ reports — and
     * being the owner's choice, it is assignable by hand too. Only ambient circles are withheld.
     */
    @Test
    fun aReviewCircleCountsTowardCircleStateAndIsHandAssignable() {
        val moments = circle(grantOn = CircleGrantOn.Review)

        assertTrue(moments.isPersonalCircle())
        assertFalse(moments.isAmbientCircle())
    }

    @Test
    fun ambientCirclesAreNotHandAssignable() {
        assertTrue(circle(grantOn = CircleGrantOn.Connect).isAmbientCircle())
        assertTrue(circle(grantOn = CircleGrantOn.OwnFlowConnect).isAmbientCircle())
        assertTrue(circle(id = AUTO_CONNECTIONS_CIRCLE_ID).isAmbientCircle())
    }

    @Test
    fun audienceAndVendorCirclesNeverCount() {
        assertFalse(circle(designation = CircleDesignation.Audience).isPersonalCircle())
        assertFalse(circle(designation = CircleDesignation.Vendor).isPersonalCircle())
    }
}

/**
 * Pinned against `ClearReviewAsync` rather than against [isPersonalCircle]: the two differ on
 * OwnFlowConnect, and a client that guesses wrong offers an un-review the server refuses.
 */
class UnreviewBlockingTest {

    private fun circle(
        id: String = "aa",
        grantOn: CircleGrantOn = CircleGrantOn.None,
        designation: CircleDesignation = CircleDesignation.Personal,
    ) = RedactedCircleDefinition(id = id, name = "c", grantOn = grantOn, designation = designation)

    @Test
    fun aPersonalCircleBlocks() {
        assertTrue(circle().blocksUnreview())
    }

    @Test
    fun aReviewCircleBlocks() {
        assertTrue(circle(grantOn = CircleGrantOn.Review).blocksUnreview())
    }

    /** The server's one carve-out: Connect, and only Connect. */
    @Test
    fun anAutoConnectCircleDoesNotBlock() {
        assertFalse(circle(grantOn = CircleGrantOn.Connect).blocksUnreview())
    }

    /** Where this parts company with isPersonalCircle — the server has no such carve-out. */
    @Test
    fun anOwnFlowConnectCircleBlocksEvenThoughItIsNotAPersonalCircleHere() {
        val vendor = circle(grantOn = CircleGrantOn.OwnFlowConnect)

        assertTrue(vendor.blocksUnreview())
        assertFalse(vendor.isPersonalCircle())
    }

    @Test
    fun systemCirclesAreSkipped() {
        assertFalse(circle(id = AUTO_CONNECTIONS_CIRCLE_ID).blocksUnreview())
        assertFalse(circle(id = CONFIRMED_CONNECTIONS_CIRCLE_ID).blocksUnreview())
    }

    @Test
    fun anAudienceCircleDoesNotBlock() {
        assertFalse(circle(designation = CircleDesignation.Audience).blocksUnreview())
    }
}
