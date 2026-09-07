package id.homebase.core.contactbook

import id.homebase.api.client.connections.CircleDesignation
import id.homebase.api.client.connections.CircleGrantOn
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.chat.services.convo.contact.CircleMembershipState
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.ui.screens.contactbook.ReviewCircleGroups
import id.homebase.core.ui.screens.contactbook.detail.ContactCircleUi
import id.homebase.core.ui.screens.contactbook.reviewCircleGroups
import id.homebase.core.config.EMERGENCY_LOCATION_CIRCLE_ID
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.core.ui.screens.contactbook.assignableCircles
import id.homebase.core.ui.screens.contactbook.isAppDefaultCircle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        emoji: String? = null,
    ) = RedactedCircleDefinition(
        id = id,
        name = name,
        disabled = disabled,
        grantOn = grantOn,
        designation = designation,
        appId = appId,
        emoji = emoji,
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

    /**
     * A ZWJ sequence is a single user-perceived glyph made of several codepoints. It has to reach
     * the UI byte-identical — any truncation on the way splits it into unrelated people.
     */
    @Test
    fun aZwjEmojiReachesTheUiIntact() {
        val family = "\uD83E\uDDD1\u200D\uD83E\uDDD1\u200D\uD83E\uDDD2\u200D\uD83E\uDDD2"
        val ui = state(circle("gg", "Family", emoji = family)).assignableCircles().single()

        assertEquals(family, ui.emoji)
        assertEquals("Family", ui.name)
    }

    @Test
    fun aCircleWithoutAnEmojiCarriesNull() {
        assertNull(state(circle("hh", "Buddies")).assignableCircles().single().emoji)
    }
}

class ReviewCircleGroupsTest {

    private fun circle(
        id: String,
        name: String,
        grantOn: CircleGrantOn = CircleGrantOn.None,
        designation: CircleDesignation = CircleDesignation.Personal,
    ) = RedactedCircleDefinition(id = id, name = name, grantOn = grantOn, designation = designation)

    private fun state(vararg defs: RedactedCircleDefinition) =
        CircleMembershipState(isLoaded = true, circles = defs.map { CircleWithMembers(circle = it) })

    @Test
    fun theThreeGroupsSplitOnGrantOnAndTheSpecialId() {
        val groups = state(
            circle("aa", "Friends"),
            circle(EMERGENCY_LOCATION_CIRCLE_ID, "Emergency Location Access"),
            circle("cc", "Moments", grantOn = CircleGrantOn.Review),
        ).reviewCircleGroups()

        assertEquals(listOf("Friends"), groups.yours.map { it.name })
        assertEquals(listOf("Emergency Location Access"), groups.special.map { it.name })
        assertEquals(listOf("Moments"), groups.appDefaults.map { it.name })
    }

    /**
     * The gap this replaced: a review circle counts toward the Circle state, so a review that
     * cannot enrol one can never produce that state through the circle the spec names.
     */
    @Test
    fun aReviewCircleIsOfferedRatherThanFilteredOut() {
        val groups = state(circle("cc", "Moments", grantOn = CircleGrantOn.Review)).reviewCircleGroups()

        assertFalse(groups.isEmpty)
        assertEquals(listOf("Moments"), groups.appDefaults.map { it.name })
    }

    @Test
    fun ambientAudienceAndVendorCirclesAreOfferedInNoGroup() {
        val groups = state(
            circle("aa", "Chat", grantOn = CircleGrantOn.Connect),
            circle("bb", "Vendor", grantOn = CircleGrantOn.OwnFlowConnect),
            circle("cc", "Subscribers", designation = CircleDesignation.Audience),
            circle("dd", "Bank", designation = CircleDesignation.Vendor),
            circle(AUTO_CONNECTIONS_CIRCLE_ID, "Auto Connections"),
        ).reviewCircleGroups()

        assertTrue(groups.isEmpty)
    }
}

class ReviewSelectionTest {

    private fun ui(id: String) = ContactCircleUi(id = id, name = id, pending = false)

    private val groups = ReviewCircleGroups(
        yours = listOf(ui("friends"), ui("family")),
        special = listOf(ui("emergency")),
        appDefaults = listOf(ui("moments"), ui("feed")),
    )

    /** The app nominated them, so the review opens with them checked. */
    @Test
    fun theSheetOpensWithTheAppDefaultsChecked() {
        assertEquals(setOf("moments", "feed"), groups.initialSelection())
    }

    /** "Chat only" has to grant nothing, so the defaults cannot ride along on an empty review. */
    @Test
    fun clearingTheLastChosenCircleAlsoClearsTheAppDefaults() {
        val picked = groups.toggleSelection(groups.initialSelection(), "friends")
        assertEquals(setOf("moments", "feed", "friends"), picked)

        assertEquals(emptySet(), groups.toggleSelection(picked, "friends"))
    }

    @Test
    fun clearingOneOfSeveralChosenCirclesLeavesTheDefaultsAlone() {
        val picked = setOf("moments", "feed", "friends", "family")

        assertEquals(setOf("moments", "feed", "friends"), groups.toggleSelection(picked, "family"))
    }

    /** Special access is a chosen circle like any other, so it holds the defaults on its own. */
    @Test
    fun specialAccessCountsAsAChosenCircle() {
        val picked = groups.toggleSelection(groups.initialSelection(), "emergency")

        assertEquals(setOf("moments", "feed", "emergency"), picked)
    }

    /** Turning a default off directly is the deliberate opt-out, and cascades to nothing. */
    @Test
    fun turningAnAppDefaultOffNeverCascades() {
        assertEquals(setOf("feed"), groups.toggleSelection(setOf("moments", "feed"), "moments"))
    }
}
