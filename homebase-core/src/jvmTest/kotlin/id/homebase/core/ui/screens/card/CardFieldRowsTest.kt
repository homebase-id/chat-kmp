@file:OptIn(ExperimentalUuidApi::class, ExperimentalTestApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.core.ui.screens.profile.ProfileAudience
import id.homebase.core.ui.screens.profile.toAcl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CardFieldRowsTest {

    private val friends = CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends")
    private val circles = listOf(CardCircle(FRIENDS_CIRCLE_ID, "Friends"))

    private fun field(type: String, key: String, value: String) = ProfileAttribute(
        id = Uuid.random(),
        type = type,
        versionTag = Uuid.random(),
        visibility = ProfileVisibility.ANONYMOUS,
        data = JsonObject(mapOf(key to JsonPrimitive(value))),
        acl = AccessControlList(requiredSecurityGroup = ProfileVisibility.ANONYMOUS.wireValue),
    )

    private fun link(text: String) = ProfileAttribute(
        id = Uuid.random(),
        type = ProfileAttributeTypes.LINK,
        versionTag = Uuid.random(),
        visibility = ProfileVisibility.ANONYMOUS,
        data = JsonObject(mapOf(
            ProfileAttributeTypes.KEY_LINK_TEXT to JsonPrimitive(text),
            ProfileAttributeTypes.KEY_LINK_TARGET to JsonPrimitive("https://shire.example/$text"),
        )),
        acl = AccessControlList(requiredSecurityGroup = ProfileVisibility.ANONYMOUS.wireValue),
    )

    private val bio = field(ProfileAttributeTypes.BIO_SUMMARY, ProfileAttributeTypes.KEY_SHORT_BIO, "Second breakfast")
    private val status = field(ProfileAttributeTypes.STATUS, ProfileAttributeTypes.KEY_STATUS, "Elevenses")
    private val phone = field(ProfileAttributeTypes.PHONE, ProfileAttributeTypes.KEY_PHONE, "+14155550123")
    private val attributes = listOf(bio, status, phone)

    private fun androidx.compose.ui.test.ComposeUiTest.editor(
        fields: CardFields?,
        shown: MutableList<Pair<Uuid, Boolean>> = mutableListOf(),
        reordered: MutableList<List<Uuid>> = mutableListOf(),
        audiences: MutableList<Pair<Uuid, ProfileAudience>> = mutableListOf(),
        attributes: List<ProfileAttribute> = this@CardFieldRowsTest.attributes,
        circles: List<CardCircle> = this@CardFieldRowsTest.circles,
    ) {
        var current by mutableStateOf(fields)
        var records by mutableStateOf(attributes)
        val known = attributes.map { it.id.toString() }.toSet()
        setContent {
            MaterialTheme {
                CardContentEditor(
                    card = friends,
                    items = cardContentItems(records, circles, friends, current),
                    circles = circles,
                    photo = CardPhotoUi(photo = null, own = false, canChange = true, busy = false),
                    enabled = true,
                    importSources = emptyList(),
                    showCardsButton = true,
                    onAudience = { id, audience ->
                        audiences += id to audience
                        records = records.map { if (it.id == id) it.copy(visibility = audience.visibility, acl = audience.toAcl()) else it }
                    },
                    onEdit = { _, _, _ -> },
                    onDelete = {},
                    onAdd = { _, _ -> },
                    onChangePhoto = {},
                    onRemovePhoto = {},
                    onImport = {},
                    onFieldShown = { id, on ->
                        shown += id to on
                        current = current.withHidden(id.toString(), !on, known)
                    },
                    onFieldsReordered = { reordered += it },
                )
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.row(section: String, text: String) = onNode(hasTestTag(section) and hasText(text))

    private fun androidx.compose.ui.test.ComposeUiTest.moveActions() =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsActions.CustomActions] }
            .filter { it.label.startsWith("Move") }

    @Test
    fun aCircleCardListsFrontBackAndNotOnThisCardAsPlainRows() = runComposeUiTest {
        val hiddenLink = link("hidden")
        editor(CardFields(hidden = listOf(status.id, hiddenLink.id).map { it.toString() }), attributes = listOf(bio, status, phone, hiddenLink, ownerOnly))

        row("field_front", "Second breakfast").assertExists()
        row("field_back", "Elevenses").assertExists()
        row("field_back", "hidden").assertExists()
        row("field_back", "Phone").assertExists()
        row("field_unavailable", "private").assertExists()
        assertEquals(1, onAllNodesWithTag("field_front").fetchSemanticsNodes().size)
        assertEquals(3, onAllNodesWithTag("field_back").fetchSemanticsNodes().size)
        assertEquals(1, onAllNodesWithTag("field_unavailable").fetchSemanticsNodes().size)
        listOf("On the card", "Shared, not shown", "Not shared", "People with this card can't see these").forEach { onNodeWithText(it).assertExists() }
        listOf("cards_button", "field_toggle", "field_handle", "never_on_card", "not_visible", "unavailable_toggle").forEach {
            assertEquals(0, onAllNodesWithTag(it, useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    private val ownerOnly = link("private").copy(
        visibility = ProfileVisibility.OWNER,
        acl = AccessControlList(requiredSecurityGroup = ProfileVisibility.OWNER.wireValue),
    )

    private val officeId = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"

    private val office = CardCircle(officeId, "Office peeps")

    private val officeOnly = link("office").copy(
        visibility = ProfileVisibility.CONNECTED,
        acl = AccessControlList(requiredSecurityGroup = ProfileVisibility.CONNECTED.wireValue, circleIdList = listOf(officeId)),
    )

    private fun ProfileAttribute.scoped(vararg ids: String) = copy(
        visibility = ProfileVisibility.CONNECTED,
        acl = AccessControlList(requiredSecurityGroup = ProfileVisibility.CONNECTED.wireValue, circleIdList = ids.toList()),
    )

    private fun ProfileAttribute.ownerOnly() = copy(
        visibility = ProfileVisibility.OWNER,
        acl = AccessControlList(requiredSecurityGroup = ProfileVisibility.OWNER.wireValue),
    )

    private fun androidx.compose.ui.test.ComposeUiTest.moveButton(tag: String, label: String) = onNode(
        hasTestTag(tag) and hasContentDescription(if (tag == "field_to_back") "Move $label to Shared, not shown" else "Show $label on the card"),
    )

    private fun androidx.compose.ui.test.ComposeUiTest.removeButton(label: String) =
        onNode(hasTestTag("field_remove") and hasContentDescription("Stop sharing $label with this card"))

    @Test
    fun theArrowsMoveAFrontRowToTheBackAndBackAgainWithoutTouchingItsAudience() = runComposeUiTest {
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        val audiences = mutableListOf<Pair<Uuid, ProfileAudience>>()
        editor(null, shown, audiences = audiences)

        moveButton("field_to_back", "Status").performClick()
        waitForIdle()
        row("field_front", "Elevenses").assertDoesNotExist()
        row("field_back", "Elevenses").assertExists()
        moveButton("field_to_front", "Status").performClick()
        waitForIdle()

        row("field_front", "Elevenses").assertExists()
        row("field_back", "Elevenses").assertDoesNotExist()
        assertEquals(listOf(status.id to false, status.id to true), shown)
        assertEquals(emptyList(), audiences)
    }

    @Test
    fun theMinusOnAFrontAndABackRowTakesTheCircleOffTheirAudience() = runComposeUiTest {
        val shared = bio.scoped(FRIENDS_CIRCLE_ID, officeId)
        val friendsOnly = status.scoped(FRIENDS_CIRCLE_ID)
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        val audiences = mutableListOf<Pair<Uuid, ProfileAudience>>()
        editor(CardFields(hidden = listOf(friendsOnly.id.toString())), shown, audiences = audiences, attributes = listOf(shared, friendsOnly), circles = circles + office)

        row("field_front", "Second breakfast").assertExists()
        row("field_back", "Elevenses").assertExists()
        removeButton("Bio").performClick()
        waitForIdle()
        removeButton("Status").performClick()
        waitForIdle()

        row("field_unavailable", "Second breakfast").assertExists()
        row("field_unavailable", "Elevenses").assertExists()
        assertEquals(0, onAllNodesWithTag("field_front").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithTag("field_back").fetchSemanticsNodes().size)
        assertEquals(listOf(shared.id to ProfileAudience.Circles(setOf(officeId)), friendsOnly.id to ProfileAudience.OnlyMe), audiences)
        assertEquals(listOf(friendsOnly.id to true), shown)
    }

    @Test
    fun noMinusOnAPublicOrEveryConnectionField() = runComposeUiTest {
        val everyConnection = status.copy(
            visibility = ProfileVisibility.CONNECTED,
            acl = AccessControlList(requiredSecurityGroup = ProfileVisibility.CONNECTED.wireValue),
        )
        val scoped = link("scoped").scoped(FRIENDS_CIRCLE_ID)
        editor(null, attributes = listOf(bio, everyConnection, scoped))

        row("field_front", "Second breakfast").assertExists()
        row("field_front", "Elevenses").assertExists()
        assertEquals(1, onAllNodesWithTag("field_remove", useUnmergedTree = true).fetchSemanticsNodes().size)
        removeButton("Link").assertExists()
        assertEquals(3, onAllNodesWithTag("field_to_back", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun tappingARowOpensEditingAndDoesNotMoveIt() = runComposeUiTest {
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        editor(CardFields(hidden = listOf(status.id.toString())), shown)

        row("field_front", "Second breakfast").performClick()
        waitForIdle()
        onNodeWithText("Edit").assertExists()
        assertEquals(emptyList(), shown)
    }

    @Test
    fun aTapOnTheDragHandleNeverMovesTheRowToTheBack() = runComposeUiTest {
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        editor(null, shown, attributes = listOf(link("one"), link("two")))

        assertEquals(2, onAllNodesWithTag("field_to_back", useUnmergedTree = true).fetchSemanticsNodes().size)
        onAllNodesWithTag("field_handle", useUnmergedTree = true)[0].performClick()
        waitForIdle()

        assertEquals(emptyList(), shown)
        assertEquals(2, onAllNodesWithTag("field_front").fetchSemanticsNodes().size)
    }

    @Test
    fun tappingANotOnThisCardRowOffersOnTheFrontOrOnTheBackAndPutsItThere() = runComposeUiTest {
        val privateStatus = status.ownerOnly()
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        val audiences = mutableListOf<Pair<Uuid, ProfileAudience>>()
        editor(null, shown, audiences = audiences, attributes = listOf(bio, ownerOnly, privateStatus))

        row("field_unavailable", "private").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).performClick()
        waitForIdle()
        onNodeWithText("Add Link to this card").assertExists()
        onNodeWithTag("place_front").assert(hasText("Show on the card") and hasText("Shown on the card face"))
        onNodeWithTag("place_back").assert(hasText("Share only") and hasText("They get it, but it isn't on the card face"))
        assertEquals(0, onAllNodes(hasClickAction() and (hasText("Front") or hasText("Back"))).fetchSemanticsNodes().size)
        onNodeWithText("Edit").assertDoesNotExist()
        onNodeWithTag("place_front").performClick()
        waitForIdle()

        row("field_front", "private").assertExists()
        row("field_unavailable", "private").assertDoesNotExist()
        onNodeWithText("Add Link to this card").assertDoesNotExist()

        row("field_unavailable", "Elevenses").performClick()
        waitForIdle()
        onNodeWithTag("place_back").performClick()
        waitForIdle()

        row("field_back", "Elevenses").assertExists()
        row("field_unavailable", "Elevenses").assertDoesNotExist()
        assertEquals(0, onAllNodesWithTag("field_unavailable").fetchSemanticsNodes().size)
        val toFriends: ProfileAudience = ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID))
        assertEquals(listOf(ownerOnly.id to toFriends, privateStatus.id to toFriends), audiences)
        assertEquals(listOf(ownerOnly.id to true, privateStatus.id to false), shown)
    }

    @Test
    fun cancelLeavesTheFieldNotOnThisCard() = runComposeUiTest {
        val audiences = mutableListOf<Pair<Uuid, ProfileAudience>>()
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        editor(null, shown, audiences = audiences, attributes = listOf(bio, ownerOnly))

        row("field_unavailable", "private").performClick()
        waitForIdle()
        onNodeWithText("Cancel").performClick()
        waitForIdle()

        onNodeWithText("Add Link to this card").assertDoesNotExist()
        row("field_unavailable", "private").assertExists()
        assertEquals(emptyList(), audiences)
        assertEquals(emptyList(), shown)
    }

    @Test
    fun aFieldTheCardCantDrawOffersOnlyShareOnly() = runComposeUiTest {
        val privatePhone = phone.ownerOnly()
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        val audiences = mutableListOf<Pair<Uuid, ProfileAudience>>()
        editor(null, shown, audiences = audiences, attributes = listOf(bio, privatePhone))

        row("field_unavailable", "Phone").performClick()
        waitForIdle()

        onNodeWithText("Add Phone to this card").assertExists()
        onNodeWithTag("place_front").assertIsNotEnabled().assert(hasText("This kind of field can't be drawn on the card face"))
        onNodeWithTag("place_back").performClick()
        waitForIdle()

        row("field_back", "Phone").assertExists()
        row("field_unavailable", "Phone").assertDoesNotExist()
        assertEquals(listOf<Pair<Uuid, ProfileAudience>>(privatePhone.id to ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID))), audiences)
        assertEquals(listOf(privatePhone.id to false), shown)
    }

    @Test
    fun noRowShowsAnAudienceIcon() = runComposeUiTest {
        editor(null, attributes = listOf(bio, status, phone, ownerOnly, officeOnly), circles = circles + office)

        row("field_unavailable", "private").assertExists()
        val rowIcons = listOf("field_front", "field_back", "field_unavailable").flatMap { tag ->
            onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true)
                .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.ContentDescription] }
        }
        assertEquals(emptyList(), rowIcons.filterNot { it.startsWith("Stop sharing ") || it.startsWith("Move ") || it.startsWith("Show ") })
    }

    @Test
    fun notOnThisCardIsHiddenWhenNothingIsUnavailable() = runComposeUiTest {
        editor(null)

        assertEquals(0, onAllNodesWithTag("field_unavailable").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("People with this card can't see these").fetchSemanticsNodes().size)
    }

    @Test
    fun anOwnerOnlyFieldSaysOnlyYouAndACircleOnlyFieldNamesTheCircle() = runComposeUiTest {
        editor(null, attributes = listOf(bio, ownerOnly, officeOnly), circles = circles + office)

        row("field_unavailable", "private").assert(hasText("Only you", substring = true))
        row("field_unavailable", "office").assert(hasText("Only Office peeps", substring = true))
    }

    @Test
    fun aFieldTheCardCantDrawSitsOnTheBackWithNoUpArrow() = runComposeUiTest {
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        editor(null, shown)

        assertEquals(0, onAllNodesWithTag("field_to_front", useUnmergedTree = true).fetchSemanticsNodes().size)
        row("field_back", "Phone").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).performClick()
        waitForIdle()

        assertEquals(emptyList(), shown)
    }

    @Test
    fun theAddButtonOffersOnlyNewFields() = runComposeUiTest {
        editor(CardFields(hidden = listOf(status.id.toString())))

        onNodeWithTag("content_add").performClick()
        waitForIdle()

        onNodeWithContentDescription("Add new: Link").assertExists()
        assertEquals(0, onAllNodesWithContentDescription("Show Status on this card").fetchSemanticsNodes().size)
    }

    @Test
    fun anEmptyFrontPointsAtTheBack() = runComposeUiTest {
        editor(CardFields(hidden = listOf(bio.id, status.id).map { it.toString() }))

        onNodeWithText("Use the up arrow on a shared field to show it on the card").assertExists()
        assertEquals(0, onAllNodesWithTag("field_front").fetchSemanticsNodes().size)
    }

    @Test
    fun theCircleLineIsAbsentWhenTheCardShowsEverythingThatCanBeShown() = runComposeUiTest {
        editor(null, attributes = listOf(bio, status))

        assertEquals(0, onAllNodesWithText("Not shared").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithTag("never_on_card").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Shared, not shown").fetchSemanticsNodes().size)
    }

    @Test
    fun switchingAFieldOffReportsItAndNeverTouchesWhoCanSeeIt() = runComposeUiTest {
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        val audiences = mutableListOf<Pair<Uuid, ProfileAudience>>()
        editor(null, shown, audiences = audiences)

        moveButton("field_to_back", "Status").performClick()

        assertEquals(listOf(status.id to false), shown)
        assertEquals(emptyList(), audiences)
        assertEquals(ProfileAudience.Public, cardContentItems(attributes, circles, friends, null).single { it.id == status.id }.audience)
    }

    @Test
    fun theNameStatusAndBioAreNotReorderable() = runComposeUiTest {
        editor(null)

        assertEquals(0, onAllNodesWithTag("field_handle").fetchSemanticsNodes().size)
        assertEquals(emptyList(), moveActions())
    }

    @Test
    fun aLinkOffersMoveDownToScreenReadersAndKeyboardsAndReportsTheNewOrder() = runComposeUiTest {
        val one = link("one")
        val two = link("two")
        val reordered = mutableListOf<List<Uuid>>()
        editor(null, reordered = reordered, attributes = listOf(one, two))

        val actions = moveActions()
        assertEquals(listOf("down", "up"), actions.map { it.label.substringAfterLast(' ') })
        actions.first { it.label.endsWith("down") }.action()
        waitForIdle()

        assertEquals(listOf(listOf(two.id, one.id)), reordered)
    }

    @Test
    fun aDragAfterAMoveActionStartsFromTheOrderTheMoveSaved() = runComposeUiTest {
        val a = link("a")
        val b = link("b")
        val c = link("c")
        val reordered = mutableListOf<List<Uuid>>()
        var fields by mutableStateOf<CardFields?>(null)
        setContent {
            MaterialTheme {
                CardContentEditor(
                    card = friends,
                    items = cardContentItems(listOf(a, b, c), circles, friends, fields),
                    circles = circles,
                    photo = CardPhotoUi(photo = null, own = false, canChange = true, busy = false),
                    enabled = true,
                    importSources = emptyList(),
                    showCardsButton = true,
                    onAudience = { _, _ -> },
                    onEdit = { _, _, _ -> },
                    onDelete = {},
                    onAdd = { _, _ -> },
                    onChangePhoto = {},
                    onRemovePhoto = {},
                    onImport = {},
                    onFieldShown = { _, _ -> },
                    onFieldsReordered = { reordered += it; fields = CardFields(order = it.map { id -> id.toString() }) },
                )
            }
        }

        moveActions().first { it.label.endsWith("down") }.action()
        waitForIdle()
        assertEquals(listOf(listOf(b.id, a.id, c.id)), reordered)

        val handles = onAllNodesWithTag("field_handle", useUnmergedTree = true)
        val pitch = with(density) { (handles[2].getBoundsInRoot().top - handles[1].getBoundsInRoot().top).toPx() }
        handles[2].performTouchInput {
            down(center)
            repeat(8) { moveBy(androidx.compose.ui.geometry.Offset(0f, -pitch * 0.1f)) }
            up()
        }
        waitForIdle()

        assertEquals(listOf(b.id, a.id, c.id), reordered[0])
        assertEquals(listOf(b.id, c.id, a.id), reordered.last())
    }

    @Test
    fun aDragBackToTheOriginalOrderAfterAMoveIsStillSaved() = runComposeUiTest {
        val a = link("a")
        val b = link("b")
        val c = link("c")
        val reordered = mutableListOf<List<Uuid>>()
        var fields by mutableStateOf<CardFields?>(null)
        setContent {
            MaterialTheme {
                CardContentEditor(
                    card = friends,
                    items = cardContentItems(listOf(a, b, c), circles, friends, fields),
                    circles = circles,
                    photo = CardPhotoUi(photo = null, own = false, canChange = true, busy = false),
                    enabled = true,
                    importSources = emptyList(),
                    showCardsButton = true,
                    onAudience = { _, _ -> },
                    onEdit = { _, _, _ -> },
                    onDelete = {},
                    onAdd = { _, _ -> },
                    onChangePhoto = {},
                    onRemovePhoto = {},
                    onImport = {},
                    onFieldShown = { _, _ -> },
                    onFieldsReordered = { reordered += it; fields = CardFields(order = it.map { id -> id.toString() }) },
                )
            }
        }

        moveActions().first { it.label.endsWith("down") }.action()
        waitForIdle()
        assertEquals(listOf(listOf(b.id, a.id, c.id)), reordered)

        val handles = onAllNodesWithTag("field_handle", useUnmergedTree = true)
        val pitch = with(density) { (handles[1].getBoundsInRoot().top - handles[0].getBoundsInRoot().top).toPx() }
        handles[1].performTouchInput {
            down(center)
            repeat(8) { moveBy(androidx.compose.ui.geometry.Offset(0f, -pitch * 0.1f)) }
            up()
        }
        waitForIdle()

        assertEquals(listOf(b.id, a.id, c.id), reordered[0])
        assertEquals(listOf(a.id, b.id, c.id), reordered.last())
    }
}
