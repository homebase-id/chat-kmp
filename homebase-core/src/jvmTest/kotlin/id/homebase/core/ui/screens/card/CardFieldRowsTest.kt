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
        edits: MutableList<Pair<Uuid, ProfileAudience>> = mutableListOf(),
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
                    showCardsButton = true,
                    onAudience = { id, audience ->
                        audiences += id to audience
                        records = records.map { if (it.id == id) it.copy(visibility = audience.visibility, acl = audience.toAcl()) else it }
                    },
                    onEdit = { id, _, audience -> edits += id to audience },
                    onDelete = {},
                    onAdd = { _, _ -> },
                    onChangePhoto = {},
                    onRemovePhoto = {},
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
    fun everyFrontAndBackRowHasAMinusIncludingPublicAndEveryConnectionFields() = runComposeUiTest {
        val everyConnection = status.copy(
            visibility = ProfileVisibility.CONNECTED,
            acl = AccessControlList(requiredSecurityGroup = ProfileVisibility.CONNECTED.wireValue),
        )
        val scoped = link("scoped").scoped(FRIENDS_CIRCLE_ID)
        editor(null, attributes = listOf(bio, everyConnection, scoped))

        row("field_front", "Second breakfast").assertExists()
        row("field_front", "Elevenses").assertExists()
        assertEquals(3, onAllNodesWithTag("field_remove", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(3, onAllNodesWithTag("field_to_back", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theMinusOnAPublicFrontRowKeepsItOnTheOtherCirclesAndMovesItToNotShared() = runComposeUiTest {
        val audiences = mutableListOf<Pair<Uuid, ProfileAudience>>()
        editor(
            null,
            audiences = audiences,
            attributes = listOf(bio),
            circles = circles + CardCircle("family-circle", "Family"),
        )

        removeButton("Bio").performClick()
        waitForIdle()

        assertEquals(listOf<Pair<Uuid, ProfileAudience>>(bio.id to ProfileAudience.Circles(setOf("family-circle"))), audiences)
        assertEquals(0, onAllNodesWithTag("field_front").fetchSemanticsNodes().size)
        onNodeWithText("Not shared").assertExists()
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
    fun thePlusOnANotSharedRowPutsItOnTheFrontOrTheBackWithNoDialog() = runComposeUiTest {
        val privatePhone = phone.ownerOnly()
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        val audiences = mutableListOf<Pair<Uuid, ProfileAudience>>()
        editor(null, shown, audiences = audiences, attributes = listOf(bio, ownerOnly, privatePhone))

        onNode(hasTestTag("field_add") and hasContentDescription("Add Link to this card")).performClick()
        waitForIdle()
        onNode(hasTestTag("field_add") and hasContentDescription("Add Phone to this card")).performClick()
        waitForIdle()

        row("field_front", "private").assertExists()
        row("field_back", "Phone").assertExists()
        assertEquals(0, onAllNodesWithTag("field_unavailable").fetchSemanticsNodes().size)
        val toFriends: ProfileAudience = ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID))
        assertEquals(listOf(ownerOnly.id to toFriends, privatePhone.id to toFriends), audiences)
        assertEquals(listOf(ownerOnly.id to true, privatePhone.id to false), shown)
    }

    @Test
    fun noRowShowsAnAudienceIcon() = runComposeUiTest {
        editor(null, attributes = listOf(bio, status, phone, ownerOnly, officeOnly), circles = circles + office)

        row("field_unavailable", "private").assertExists()
        val rowIcons = listOf("field_front", "field_back", "field_unavailable").flatMap { tag ->
            onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true)
                .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.ContentDescription] }
        }
        assertEquals(emptyList(), rowIcons.filterNot { it.startsWith("Stop sharing ") || it.startsWith("Move ") || it.startsWith("Show ") || it.startsWith("Add ") })
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
                    showCardsButton = true,
                    onAudience = { _, _ -> },
                    onEdit = { _, _, _ -> },
                    onDelete = {},
                    onAdd = { _, _ -> },
                    onChangePhoto = {},
                    onRemovePhoto = {},
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
                    showCardsButton = true,
                    onAudience = { _, _ -> },
                    onEdit = { _, _, _ -> },
                    onDelete = {},
                    onAdd = { _, _ -> },
                    onChangePhoto = {},
                    onRemovePhoto = {},
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

    private fun androidx.compose.ui.test.ComposeUiTest.profileEditor(
        card: CardAudience,
        attributes: List<ProfileAttribute>,
        showCardsButton: Boolean = true,
        shown: MutableList<Pair<Uuid, Boolean>> = mutableListOf(),
        audiences: MutableList<Pair<Uuid, ProfileAudience>> = mutableListOf(),
    ) {
        val known = attributes.map { it.id.toString() }.toSet()
        var current by mutableStateOf<CardFields?>(null)
        setContent {
            MaterialTheme {
                CardContentEditor(
                    card = card,
                    items = cardContentItems(attributes, circles, card, current),
                    circles = circles,
                    photo = CardPhotoUi(photo = null, own = false, canChange = true, busy = false),
                    enabled = true,
                    showCardsButton = showCardsButton,
                    onAudience = { id, audience -> audiences += id to audience },
                    onEdit = { _, _, _ -> },
                    onDelete = {},
                    onAdd = { _, _ -> },
                    onChangePhoto = {},
                    onRemovePhoto = {},
                    onFieldShown = { id, on ->
                        shown += id to on
                        current = current.withHidden(id.toString(), !on, known)
                    },
                )
            }
        }
    }

    private val name = field(ProfileAttributeTypes.NAME, ProfileAttributeTypes.KEY_GIVEN_NAME, "Frodo")

    @Test
    fun aPublicCardMinusTakesTheRowOffThisCardOnlyAndNeverOnTheNameRow() = runComposeUiTest {
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        val audiences = mutableListOf<Pair<Uuid, ProfileAudience>>()
        profileEditor(CardAudience.Public, listOf(name, bio), shown = shown, audiences = audiences)

        assertEquals(1, onAllNodesWithTag("field_remove", useUnmergedTree = true).fetchSemanticsNodes().size)
        onNode(hasTestTag("field_remove") and hasContentDescription("Hide Bio from this card")).performClick()
        waitForIdle()

        assertEquals(listOf(bio.id to false), shown)
        assertEquals(emptyList(), audiences)
        assertEquals(1, onAllNodesWithTag("field_add", useUnmergedTree = true).fetchSemanticsNodes().size)
        onNode(hasTestTag("field_add") and hasContentDescription("Show Bio on this card")).performClick()
        waitForIdle()
        assertEquals(listOf(bio.id to false, bio.id to true), shown)
    }

    @Test
    fun aPublicCardPlusOnAnUnsharedRowOpensTheCardsSheetInsteadOfPublishingIt() = runComposeUiTest {
        val audiences = mutableListOf<Pair<Uuid, ProfileAudience>>()
        val privatePhone = phone.ownerOnly()
        profileEditor(CardAudience.Public, listOf(name, privatePhone), audiences = audiences)

        onNodeWithTag("not_visible_toggle").performClick()
        waitForIdle()
        onNode(hasTestTag("field_add") and hasContentDescription("Add Phone to this card")).performClick()
        waitForIdle()

        onNodeWithTag("cards_sheet_done").assertExists()
        assertEquals(emptyList(), audiences)
    }

    @Test
    fun aCircleProfileEditorPlusAddsOnlyThatCircleToAnUnsharedRow() = runComposeUiTest {
        val audiences = mutableListOf<Pair<Uuid, ProfileAudience>>()
        val privatePhone = phone.ownerOnly()
        profileEditor(friends, listOf(name, privatePhone), showCardsButton = false, audiences = audiences)

        onNodeWithTag("not_visible_toggle").performClick()
        waitForIdle()
        onNode(hasTestTag("field_add") and hasContentDescription("Add Phone to this card")).performClick()
        waitForIdle()

        assertEquals(listOf<Pair<Uuid, ProfileAudience>>(privatePhone.id to ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID))), audiences)
    }

    @Test
    fun tappingACirclePillInTheEditSheetAddsThatCircleOnSave() = runComposeUiTest {
        val edits = mutableListOf<Pair<Uuid, ProfileAudience>>()
        val scoped = bio.scoped(FRIENDS_CIRCLE_ID)
        editor(
            CardFields(),
            edits = edits,
            attributes = listOf(scoped),
            circles = circles + CardCircle("family-circle", "Family"),
        )

        row("field_front", "Second breakfast").performClick()
        waitForIdle()
        onNodeWithText("Available on:").assertExists()
        onNodeWithTag("audience_pill_Family").performClick()
        waitForIdle()
        onNodeWithText("Save").performClick()
        waitForIdle()

        assertEquals(
            listOf<Pair<Uuid, ProfileAudience>>(scoped.id to ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID, "family-circle"))),
            edits,
        )
    }
}
