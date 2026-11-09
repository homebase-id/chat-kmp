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
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
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

    private val bio = field(ProfileAttributeTypes.BIO_SUMMARY, ProfileAttributeTypes.KEY_SHORT_BIO, "Second breakfast")
    private val status = field(ProfileAttributeTypes.STATUS, ProfileAttributeTypes.KEY_STATUS, "Elevenses")
    private val attributes = listOf(bio, status)

    private fun androidx.compose.ui.test.ComposeUiTest.editor(
        fields: CardFields?,
        shown: MutableList<Pair<Uuid, Boolean>> = mutableListOf(),
        reordered: MutableList<List<Uuid>> = mutableListOf(),
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
                onAudience = { _, _ -> },
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

    @Test
    fun aFieldSwitchedOffMovesToNotOnThisCardAndTheCircleSaysItCanStillSeeIt() = runComposeUiTest {
        editor(CardFields(hidden = listOf(status.id.toString())))

        onNodeWithText("Not on this card").assertIsDisplayed()
        onNodeWithTag("circle_also_sees").assertIsDisplayed()
        onNodeWithTag("circle_also_sees").assert(hasText("Friends can also see:", substring = true))
        assertEquals(2, onAllNodesWithTag("field_toggle").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithTag("field_handle").fetchSemanticsNodes().size)
    }

    @Test
    fun switchingAFieldOffAndOnReportsItWithoutTouchingAccess() = runComposeUiTest {
        val shown = mutableListOf<Pair<Uuid, Boolean>>()
        editor(null, shown)

        onAllNodesWithTag("field_toggle")[0].performClick()

        assertEquals(1, shown.size)
        assertEquals(false, shown.single().second)
    }

    @Test
    fun aRowOffersMoveDownToScreenReadersAndKeyboardsAndReportsTheNewOrder() = runComposeUiTest {
        val reordered = mutableListOf<List<Uuid>>()
        editor(null, reordered = reordered)
        val first = cardContentItems(attributes, circles, friends, null).filter { it.onCard }.map { it.id }

        val rows = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes()
        val custom = rows.first().config[SemanticsActions.CustomActions]
        assertEquals(true, custom.any { it.label.startsWith("Move") && it.label.endsWith("down") })
        assertEquals(false, custom.any { it.label.endsWith("up") })
        custom.first { it.label.endsWith("down") }.action()
        waitForIdle()

        assertEquals(listOf(first.reversed()), reordered)
    }
}
