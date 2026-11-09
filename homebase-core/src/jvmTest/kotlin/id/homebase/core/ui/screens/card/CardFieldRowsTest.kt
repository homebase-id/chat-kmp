@file:OptIn(ExperimentalUuidApi::class, ExperimentalTestApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
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
        audiences: MutableList<Uuid> = mutableListOf(),
        attributes: List<ProfileAttribute> = this@CardFieldRowsTest.attributes,
    ) {
        var current by mutableStateOf(fields)
        val known = attributes.map { it.id.toString() }.toSet()
        setContent {
            MaterialTheme {
                CardContentEditor(
                    card = friends,
                    items = cardContentItems(attributes, circles, friends, current),
                    circles = circles,
                    photo = CardPhotoUi(photo = null, own = false, canChange = true, busy = false),
                    enabled = true,
                    importSources = emptyList(),
                    showCardsButton = true,
                    onAudience = { id, _ -> audiences += id },
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
    fun aCircleCardListsFrontBackAndNotAvailableAsPlainRows() = runComposeUiTest {
        val hiddenLink = link("hidden")
        editor(CardFields(hidden = listOf(status.id, hiddenLink.id).map { it.toString() }), attributes = listOf(bio, status, phone, hiddenLink, ownerOnly))

        row("field_front", "Second breakfast").assertExists()
        row("field_back", "Elevenses").assertExists()
        row("field_back", "hidden").assertExists()
        row("field_back", "Phone").assertExists()
        assertEquals(1, onAllNodesWithTag("field_front").fetchSemanticsNodes().size)
        assertEquals(3, onAllNodesWithTag("field_back").fetchSemanticsNodes().size)
        listOf("Front", "Back", "Not available (1)").forEach { onNodeWithText(it).assertExists() }
        expandUnavailable()
        row("field_unavailable", "private").assertExists()
        assertEquals(1, onAllNodesWithTag("field_unavailable").fetchSemanticsNodes().size)
        listOf("cards_button", "field_toggle", "field_handle", "never_on_card", "not_visible").forEach {
            assertEquals(0, onAllNodesWithTag(it, useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.expandUnavailable() {
        onNodeWithTag("unavailable_toggle").performClick()
        waitForIdle()
    }

    private val ownerOnly = link("private").copy(
        visibility = ProfileVisibility.OWNER,
        acl = AccessControlList(requiredSecurityGroup = ProfileVisibility.OWNER.wireValue),
    )

    private val officeId = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"

    private val officeOnly = link("office").copy(
        visibility = ProfileVisibility.CONNECTED,
        acl = AccessControlList(requiredSecurityGroup = ProfileVisibility.CONNECTED.wireValue, circleIdList = listOf(officeId)),
    )

    private fun androidx.compose.ui.test.ComposeUiTest.moveButton(tag: String, label: String) = onNode(
        hasTestTag(tag) and hasContentDescription(if (tag == "field_to_back") "Move $label to the back" else "Show $label on the front"),
    )

    @Test
    fun theLeadingButtonMovesAFrontRowToTheBackAndBackAgain() = runComposeUiTest {
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        val audiences = mutableListOf<Uuid>()
        editor(null, shown, audiences = audiences)

        moveButton("field_to_back", "Status").performClick()
        waitForIdle()
        row("field_front", "Elevenses").assertDoesNotExist()
        moveButton("field_to_front", "Status").performClick()
        waitForIdle()

        row("field_front", "Elevenses").assertExists()
        row("field_back", "Elevenses").assertDoesNotExist()
        assertEquals(listOf(status.id to false, status.id to true), shown)
        assertEquals(emptyList(), audiences)
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

        onAllNodesWithTag("field_handle", useUnmergedTree = true)[0].performClick()
        waitForIdle()

        assertEquals(emptyList(), shown)
        assertEquals(2, onAllNodesWithTag("field_front").fetchSemanticsNodes().size)
    }

    @Test
    fun aFieldTheCircleCantSeeIsOnlyUnderNotAvailableAndATapOpensItsEditSheet() = runComposeUiTest {
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        val audiences = mutableListOf<Uuid>()
        editor(null, shown, audiences = audiences, attributes = listOf(bio, ownerOnly))

        row("field_front", "private").assertDoesNotExist()
        row("field_back", "private").assertDoesNotExist()
        expandUnavailable()
        row("field_unavailable", "private")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .performClick()
        waitForIdle()

        onNodeWithText("Edit").assertExists()
        assertEquals(emptyList(), shown)
        assertEquals(emptyList(), audiences)
    }

    @Test
    fun notAvailableIsCollapsedUntilTheHeaderIsTapped() = runComposeUiTest {
        editor(null, attributes = listOf(bio, ownerOnly))

        onNodeWithText("Not available (1)").assertExists()
        row("field_unavailable", "private").assertDoesNotExist()
        onNodeWithText("People with this card can't see these").assertDoesNotExist()
        onNodeWithContentDescription("Expand").assertExists()

        expandUnavailable()
        row("field_unavailable", "private").assertExists()
        onNodeWithText("People with this card can't see these").assertExists()
        onNodeWithContentDescription("Collapse").assertExists()

        expandUnavailable()
        row("field_unavailable", "private").assertDoesNotExist()
    }

    @Test
    fun notAvailableIsHiddenWhenNothingIsUnavailable() = runComposeUiTest {
        editor(null)

        assertEquals(0, onAllNodesWithTag("unavailable_toggle").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Not available", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun anOwnerOnlyFieldSaysOnlyYouAndACircleOnlyFieldNamesTheCircle() = runComposeUiTest {
        val office = CardCircle(officeId, "Office peeps")
        setContent {
            MaterialTheme {
                CardContentEditor(
                    card = friends,
                    items = cardContentItems(listOf(bio, ownerOnly, officeOnly), circles + office, friends, null),
                    circles = circles + office,
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
                )
            }
        }

        expandUnavailable()
        row("field_unavailable", "private").assert(hasText("Only you", substring = true))
        row("field_unavailable", "office").assert(hasText("Only Office peeps", substring = true))
    }

    @Test
    fun savingAnAudienceThatGainsTheCircleMovesTheFieldToTheBack() = runComposeUiTest {
        val edits = mutableListOf<ProfileAudience>()
        var current by mutableStateOf(listOf(bio, ownerOnly))
        var fields by mutableStateOf<CardFields?>(null)
        setContent {
            MaterialTheme {
                CardContentEditor(
                    card = friends,
                    items = cardContentItems(current, circles, friends, fields),
                    circles = circles,
                    photo = CardPhotoUi(photo = null, own = false, canChange = true, busy = false),
                    enabled = true,
                    importSources = emptyList(),
                    showCardsButton = true,
                    onAudience = { _, _ -> },
                    onEdit = { id, _, audience ->
                        edits += audience
                        val ids = (audience as ProfileAudience.Circles).ids.toList()
                        current = current.map {
                            if (it.id != id) it else it.copy(
                                visibility = ProfileVisibility.CONNECTED,
                                acl = AccessControlList(requiredSecurityGroup = ProfileVisibility.CONNECTED.wireValue, circleIdList = ids),
                            )
                        }
                    },
                    onDelete = {},
                    onAdd = { _, _ -> },
                    onChangePhoto = {},
                    onRemovePhoto = {},
                    onImport = {},
                    onFieldShown = { id, on -> fields = fields.withHidden(id.toString(), !on, current.map { it.id.toString() }.toSet()) },
                )
            }
        }

        expandUnavailable()
        row("field_unavailable", "private").performClick()
        waitForIdle()
        onNodeWithText("Circles").performClick()
        onNodeWithText("Save").performClick()
        waitForIdle()

        assertEquals(1, edits.size)
        row("field_unavailable", "private").assertDoesNotExist()
        row("field_back", "private").assertExists()
        row("field_front", "private").assertDoesNotExist()
    }

    @Test
    fun aFieldTheCardCantDrawSitsOnTheBackWithNothingToTap() = runComposeUiTest {
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

        onNodeWithText("Use the add button on a Back field to show it on the card").assertExists()
        assertEquals(0, onAllNodesWithTag("field_front").fetchSemanticsNodes().size)
    }

    @Test
    fun theCircleLineIsAbsentWhenTheCardShowsEverythingThatCanBeShown() = runComposeUiTest {
        editor(null, attributes = listOf(bio, status))

        assertEquals(0, onAllNodesWithText("Not on this card").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithTag("never_on_card").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Back").fetchSemanticsNodes().size)
    }

    @Test
    fun switchingAFieldOffReportsItAndNeverTouchesWhoCanSeeIt() = runComposeUiTest {
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        val audiences = mutableListOf<Uuid>()
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
