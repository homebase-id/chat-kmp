package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.connections.CircleDesignation
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.ConnectionRequestOrigin
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.connections.GrantOn
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.api.common.OdinId
import id.homebase.core.ui.screens.contactbook.review.ReviewAppToggle
import id.homebase.core.ui.screens.contactbook.review.ReviewCircleOption
import id.homebase.core.ui.screens.contactbook.review.ReviewConnectionUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun circle(
    id: String,
    name: String,
    grantOn: GrantOn,
    designation: CircleDesignation,
    appId: String? = null,
    members: List<String> = emptyList(),
) = CircleWithMembers(
    circle = RedactedCircleDefinition(
        id = id,
        name = name,
        grantOn = grantOn,
        designation = designation,
        appId = appId,
    ),
    members = members.map { OdinId(it) },
)

private fun reg(
    domain: String,
    reviewedAt: Long? = null,
    vetted: Boolean = false,
) = RedactedIdentityConnectionRegistration(
    odinId = OdinId(domain),
    status = ConnectionStatus.Connected,
    created = 1,
    lastUpdated = 1,
    connectionRequestOrigin = ConnectionRequestOrigin.Introduction,
    hasVerificationHash = true,
    rku = false,
    reviewedAt = reviewedAt,
    vetted = vetted,
)

private val chatOnly = circle(
    id = "c17a1000-0000-4000-8000-000000000001",
    name = "Chat-only",
    grantOn = GrantOn.Connect,
    designation = CircleDesignation.Personal,
    appId = "2d781401-3804-4b57-b4aa-d8e4e2ef39f4",
    members = listOf("frodo.dotyou.cloud", "sam.dotyou.cloud", "bilbo.dotyou.cloud"),
)

private val family = circle(
    id = "8f1e0000-0000-4000-8000-000000000002",
    name = "Family",
    grantOn = GrantOn.None,
    designation = CircleDesignation.Personal,
    members = listOf("sam.dotyou.cloud"),
)

private val subscribers = circle(
    id = "aa000000-0000-4000-8000-000000000003",
    name = "Subscribers",
    grantOn = GrantOn.None,
    designation = CircleDesignation.Audience,
    members = listOf("bilbo.dotyou.cloud"),
)

class ContactStateTest {

    private val states = ContactStates(listOf(chatOnly, family, subscribers))

    /** The whole point of the grantOn qualifier: everyone is in the auto-connect circle. */
    @Test
    fun autoConnectCircleDoesNotPromoteToCircleState() {
        assertEquals(ContactState.New, states.stateFor(reg("frodo.dotyou.cloud")))
    }

    @Test
    fun reviewedWithNoOwnerCircleIsChat() {
        assertEquals(ContactState.Chat, states.stateFor(reg("frodo.dotyou.cloud", reviewedAt = 5)))
    }

    @Test
    fun ownerGrantedCircleIsCircleState() {
        assertEquals(ContactState.Circle, states.stateFor(reg("sam.dotyou.cloud", reviewedAt = 5)))
    }

    /** Membership is itself evidence of review — covers grants made from another surface. */
    @Test
    fun circleMembershipImpliesReviewedEvenWithoutStamp() {
        assertEquals(ContactState.Circle, states.stateFor(reg("sam.dotyou.cloud")))
    }

    @Test
    fun legacyVettedFlagStillReadsAsChat() {
        assertEquals(ContactState.Chat, states.stateFor(reg("frodo.dotyou.cloud", vetted = true)))
    }

    @Test
    fun audienceCircleNeverAwardsCircleState() {
        assertEquals(ContactState.New, states.stateFor(reg("bilbo.dotyou.cloud")))
        assertTrue(states.isAudienceMember("bilbo.dotyou.cloud"))
        assertFalse(states.isAudienceMember("frodo.dotyou.cloud"))
    }

    @Test
    fun onlyOwnerGrantedPersonalCirclesRenderAsPills() {
        val info = states.infoFor(reg("sam.dotyou.cloud", reviewedAt = 5))
        assertEquals(listOf("Family"), info.circles.map { it.name })
    }

    @Test
    fun unknownDomainIsNew() {
        assertEquals(ContactState.New, states.stateFor(reg("stranger.dotyou.cloud")))
    }
}

class ReviewEnrollmentTest {

    private val toggles = appDefaultToggles(listOf(chatOnly, family, subscribers))

    @Test
    fun ownerCirclesAreNotAppToggles() {
        assertEquals(1, toggles.size)
        assertEquals("2d781401-3804-4b57-b4aa-d8e4e2ef39f4", toggles.first().appId)
        assertTrue(toggles.first().reviewCircles.isEmpty())
        assertEquals(listOf("Chat-only"), toggles.first().connectCircles.map { it.name })
    }

    /** Rule 2: a checked app's Connect circle rides along when the contact isn't in it yet. */
    @Test
    fun checkedAppSendsItsConnectCircleWhenNotAlreadyHeld() {
        val ids = reviewEnrollment(
            selectedPersonalCircleIds = setOf(family.circle.id),
            checkedApps = toggles,
            alreadyHeldCircleIds = emptySet(),
        )
        assertEquals(listOf(family.circle.id, chatOnly.circle.id), ids)
    }

    @Test
    fun alreadyHeldConnectCircleIsNotResent() {
        val ids = reviewEnrollment(
            selectedPersonalCircleIds = setOf(family.circle.id),
            checkedApps = toggles,
            alreadyHeldCircleIds = setOf(chatOnly.circle.id),
        )
        assertEquals(listOf(family.circle.id), ids)
    }

    /** Held ids may arrive in the 32-char "N" form while definitions are hyphenated. */
    @Test
    fun heldIdsMatchAcrossGuidFormats() {
        val ids = reviewEnrollment(
            selectedPersonalCircleIds = emptySet(),
            checkedApps = toggles,
            alreadyHeldCircleIds = setOf(chatOnly.circle.id.replace("-", "").uppercase()),
        )
        assertTrue(ids.isEmpty())
    }

    /** Chat-only outcome: no circles selected, no apps checked, nothing granted. */
    @Test
    fun chatOnlyOutcomeSendsNothing() {
        val ids = reviewEnrollment(emptySet(), emptyList(), emptySet())
        assertTrue(ids.isEmpty())
    }
}


class ReviewOutcomeTest {

    private fun state(
        selected: Set<String> = emptySet(),
        checkedApps: Set<String> = emptySet(),
    ) = ReviewConnectionUiState(
        odinId = "frodo.dotyou.cloud",
        circles = listOf(ReviewCircleOption(family.circle.id, "Family", null)),
        appToggles = listOf(ReviewAppToggle("app", "Chat-only", listOf(chatOnly.circle.id))),
        selectedCircleIds = selected,
        checkedAppIds = checkedApps,
    )

    @Test
    fun noCirclesSelectedIsChatOnly() {
        assertFalse(state().addsToCircles)
    }

    @Test
    fun aSelectedCircleFlipsTheButtonToAddToCircles() {
        assertTrue(state(selected = setOf(family.circle.id)).addsToCircles)
    }

    /**
     * The label must predict the state the row will then show. An app default never produces
     * Circle (its circles are not grantOn=none), so checking one alone must not claim it does.
     */
    @Test
    fun anAppDefaultAloneDoesNotClaimCircleState() {
        assertFalse(state(checkedApps = setOf("app")).addsToCircles)
    }
}
