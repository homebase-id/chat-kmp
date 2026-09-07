package id.homebase.core.contactbook

import id.homebase.api.client.connections.CircleDesignation
import id.homebase.api.client.connections.CircleGrantOn
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.chat.services.convo.contact.CircleMembershipState
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.core.ui.screens.contactbook.assignableCircles
import id.homebase.core.ui.screens.contactbook.isAppDefaultCircle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AssignableCirclesTest {

    private fun circle(
        id: String,
        name: String,
        grantOn: CircleGrantOn = CircleGrantOn.None,
        designation: CircleDesignation = CircleDesignation.Personal,
        appId: Uuid? = null,
        disabled: Boolean = false,
    ) = RedactedCircleDefinition(
        id = id,
        name = name,
        disabled = disabled,
        grantOn = grantOn,
        designation = designation,
        appId = appId,
    )

    private fun state(vararg defs: RedactedCircleDefinition) =
        CircleMembershipState(isLoaded = true, circles = defs.map { CircleWithMembers(circle = it) })

    /**
     * The reason the legacy ids survive the move to GrantOn: a server below odin-core #1688 reports
     * None for every circle, and odin-core keeps these two at None even above it.
     */
    @Test
    fun legacySystemCirclesAreAppDefaultsEvenAtGrantOnNone() {
        assertTrue(circle(AUTO_CONNECTIONS_CIRCLE_ID, "Auto Connections").isAppDefaultCircle())
        assertTrue(circle(CONFIRMED_CONNECTIONS_CIRCLE_ID, "Confirmed").isAppDefaultCircle())
        assertTrue(circle(AUTO_CONNECTIONS_CIRCLE_ID.uppercase(), "Auto").isAppDefaultCircle())
    }

    @Test
    fun aGrantOnMarksAnAppDefaultWithoutAnyIdKnowledge() {
        val chat = circle("55900e0ab05347dca85c5ac2514e7fd3", "Chat", grantOn = CircleGrantOn.Connect)
        assertTrue(chat.isAppDefaultCircle())
    }

    /** App ownership alone must not hide a circle — Friends and Family are contacts-app-owned. */
    @Test
    fun appOwnedRelationshipCirclesStayAssignable() {
        val friends = circle(
            id = "3d594614f445f6b00014e9b77730b833",
            name = "Friends",
            appId = Uuid.random(),
        )
        assertFalse(friends.isAppDefaultCircle())
        assertEquals(listOf("Friends"), state(friends).assignableCircles().map { it.name })
    }

    @Test
    fun audienceAndVendorCirclesAreNeverAssignableHere() {
        val subscribers = circle("aa", "Subscribers", designation = CircleDesignation.Audience)
        val bank = circle("bb", "Bank", designation = CircleDesignation.Vendor)
        val family = circle("cc", "Family")

        assertEquals(listOf("Family"), state(subscribers, bank, family).assignableCircles().map { it.name })
    }

    @Test
    fun disabledAndUnnamedCirclesAreExcluded() {
        val off = circle("dd", "Retired", disabled = true)
        val blank = circle("ee", "  ")
        val keep = circle("ff", "Buddies")

        assertEquals(listOf("Buddies"), state(off, blank, keep).assignableCircles().map { it.name })
    }
}
