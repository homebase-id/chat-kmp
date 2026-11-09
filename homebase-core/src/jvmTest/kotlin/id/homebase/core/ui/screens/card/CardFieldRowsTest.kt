@file:OptIn(ExperimentalUuidApi::class, ExperimentalTestApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
    ) = setContent {
        MaterialTheme {
            CardContentEditor(
                card = friends,
                items = cardContentItems(attributes, circles, friends, fields),
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
                onFieldShown = { id, on -> shown += id to on },
                onFieldsReordered = { reordered += it },
            )
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.moveActions() =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsActions.CustomActions] }
            .filter { it.label.startsWith("Move") }

    @Test
    fun aFieldSwitchedOffMovesToNotOnThisCardAndTheCircleListsWhatItCanStillSee() = runComposeUiTest {
        editor(CardFields(hidden = listOf(status.id.toString())))

        onNodeWithText("Not on this card").assertIsDisplayed()
        onNodeWithText("Not shown on cards").assertIsDisplayed()
        onNodeWithTag("circle_also_sees").assert(hasText("Friends can also see: Status, Phone"))
        assertEquals(2, onAllNodesWithTag("field_toggle").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithTag("field_handle").fetchSemanticsNodes().size)
    }

    @Test
    fun theCircleLineIsAbsentWhenTheCardShowsEverythingThatCanBeShown() = runComposeUiTest {
        editor(null, attributes = listOf(bio, status))

        assertEquals(0, onAllNodesWithTag("circle_also_sees").fetchSemanticsNodes().size)
    }

    @Test
    fun switchingAFieldOffReportsItAndNeverTouchesWhoCanSeeIt() = runComposeUiTest {
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        val audiences = mutableListOf<Uuid>()
        editor(null, shown, audiences = audiences)

        onAllNodesWithTag("field_toggle")[0].performClick()

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
