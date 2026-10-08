package id.homebase.core.contactbook

import id.homebase.api.client.connections.CircleDesignation
import id.homebase.api.client.connections.CircleGrantOn
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.chat.services.convo.contact.CircleMembershipState
import id.homebase.core.config.CONTACTS_APP_ID
import id.homebase.core.ui.screens.contactbook.ReviewCircleGroups
import id.homebase.core.ui.screens.contactbook.detail.ContactCircleUi
import id.homebase.core.ui.screens.contactbook.reviewCircleGroups
import id.homebase.core.config.EMERGENCY_LOCATION_CIRCLE_ID
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
    ) = RedactedCircleDefinition(
        id = id,
        name = name,
        grantOn = grantOn,
        designation = designation,
        appId = appId,
    )

    @Test
    fun aGrantOnMarksAnAppDefaultWithoutAnyIdKnowledge() {
        val chat = circle("55900e0ab05347dca85c5ac2514e7fd3", "Chat", grantOn = CircleGrantOn.Connect)
        assertTrue(chat.isAppDefaultCircle())
    }

    /** App ownership alone must not make a circle an app default — Friends and Family are contacts-app-owned. */
    @Test
    fun appOwnedRelationshipCirclesAreNotAppDefaults() {
        val friends = circle(
            id = "3d594614f445f6b00014e9b77730b833",
            name = "Friends",
            appId = Uuid.random(),
        )
        assertFalse(friends.isAppDefaultCircle())
    }
}

@OptIn(ExperimentalUuidApi::class)
class ReviewCircleGroupsTest {

    private val contactsApp = Uuid.parse(CONTACTS_APP_ID)

    private fun circle(
        id: String,
        name: String,
        grantOn: CircleGrantOn = CircleGrantOn.None,
        designation: CircleDesignation = CircleDesignation.Personal,
        appId: Uuid? = contactsApp,
        disabled: Boolean = false,
    ) = RedactedCircleDefinition(
        id = id,
        name = name,
        grantOn = grantOn,
        designation = designation,
        appId = appId,
        disabled = disabled,
    )

    private fun state(vararg defs: RedactedCircleDefinition) =
        CircleMembershipState(isLoaded = true, circles = defs.map { CircleWithMembers(circle = it) })

    @Test
    fun theThreeGroupsSplitOnGrantOnAndTheSpecialId() {
        val groups = state(
            circle("aa", "Friends"),
            circle(EMERGENCY_LOCATION_CIRCLE_ID, "Emergency Location Access", appId = null),
            circle("cc", "Moments", grantOn = CircleGrantOn.Review, appId = Uuid.random()),
        ).reviewCircleGroups()

        assertEquals(listOf("Friends"), groups.yours.map { it.name })
        assertEquals(listOf("Emergency Location Access"), groups.special.map { it.name })
        assertEquals(listOf("Moments"), groups.appDefaults.map { it.name })
    }

    /**
     * An unstamped circle is indistinguishable from an app circle odin-core never stamped, so
     * neither group offers it. Emergency Location Access is exempt: it matches on its id.
     */
    @Test
    fun circlesWithNoOwningAppAreOfferedInNoGroup() {
        val groups = state(
            circle("aa", "Recovery", appId = null),
            circle("bb", "Webdrop", grantOn = CircleGrantOn.Review, appId = null),
        ).reviewCircleGroups()

        assertTrue(groups.isEmpty)
    }

    @Test
    fun aDisabledCircleIsOfferedFlaggedAndNeverPreselected() {
        val groups = state(
            circle("aa", "Friends", disabled = true),
            circle("cc", "Moments", grantOn = CircleGrantOn.Review, appId = Uuid.random(), disabled = true),
        ).reviewCircleGroups()

        assertTrue(groups.yours.single().disabled)
        assertTrue(groups.appDefaults.single().disabled)
        assertEquals(emptySet(), groups.initialSelection())
    }

    @Test
    fun anotherAppsCirclesAreStillOfferedAsAppDefaults() {
        val groups = state(circle("aa", "Feed", grantOn = CircleGrantOn.Review, appId = Uuid.random()))
            .reviewCircleGroups()

        assertEquals(listOf("Feed"), groups.appDefaults.map { it.name })
    }

    /**
     * The gap this replaced: a review circle counts toward the Circle state, so a review that
     * cannot enrol one can never produce that state through the circle the spec names.
     */
    @Test
    fun aReviewCircleIsOfferedRatherThanFilteredOut() {
        val groups = state(circle("cc", "Moments", grantOn = CircleGrantOn.Review, appId = Uuid.random()))
            .reviewCircleGroups()

        assertFalse(groups.isEmpty)
        assertEquals(listOf("Moments"), groups.appDefaults.map { it.name })
    }

    @Test
    fun onlyAnOfferedCircleCountsAsHeld() {
        val groups = state(circle("aa", "Friends")).reviewCircleGroups()

        assertFalse(groups.holdsAnyOffered(setOf("chat")))
        assertTrue(groups.holdsAnyOffered(setOf("aa")))
    }

    @Test
    fun ambientAudienceAndVendorCirclesAreOfferedInNoGroup() {
        val groups = state(
            circle("aa", "Chat", grantOn = CircleGrantOn.Connect),
            circle("bb", "Vendor", grantOn = CircleGrantOn.OwnFlowConnect),
            circle("cc", "Subscribers", designation = CircleDesignation.Audience),
            circle("dd", "Bank", designation = CircleDesignation.Vendor),
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
