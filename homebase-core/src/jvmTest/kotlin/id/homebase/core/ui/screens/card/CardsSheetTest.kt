@file:OptIn(ExperimentalUuidApi::class, ExperimentalTestApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

class CardsSheetTest {

    private val circles = listOf(
        CardCircle(FRIENDS_CIRCLE_ID, "Friends"),
        CardCircle(FAMILY_CIRCLE_ID, "Family"),
        CardCircle(WORK_CIRCLE_ID, "Work"),
    )

    private fun item(audience: ProfileAudience, required: Boolean = false) = CardContentItem(
        id = Uuid.random(),
        type = ProfileAttributeTypes.PHONE,
        text = "+14155550123",
        audience = audience,
        shown = true,
        locked = false,
        required = required,
    )

    private fun ComposeUiTest.menu(
        item: CardContentItem,
        applied: MutableList<ProfileAudience>,
        dismissed: MutableList<Unit> = mutableListOf(),
        menuCircles: List<CardCircle> = circles,
    ) =
        setContent {
            MaterialTheme {
                Box { CardsSheet(item = item, circles = menuCircles, onApply = { applied += it }, onDismiss = { dismissed += Unit }) }
            }
        }

    private fun ComposeUiTest.circle(id: String) = onNodeWithTag("cards_sheet_circle_$id")

    @Test
    fun tickingPublicTicksAndDisablesEveryCircleAndUntickingRestoresThePreviousTicks() = runComposeUiTest {
        menu(item(ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID))), mutableListOf())

        circle(FRIENDS_CIRCLE_ID).assertIsOn().assertIsEnabled()
        circle(FAMILY_CIRCLE_ID).assertIsOff()
        circle(WORK_CIRCLE_ID).assertIsOff()

        onNodeWithTag("cards_sheet_public").performClick()
        circles.forEach { circle(it.id).assertIsOn().assertIsNotEnabled() }

        onNodeWithTag("cards_sheet_public").performClick()
        circle(FRIENDS_CIRCLE_ID).assertIsOn().assertIsEnabled()
        circle(FAMILY_CIRCLE_ID).assertIsOff().assertIsEnabled()
        circle(WORK_CIRCLE_ID).assertIsOff().assertIsEnabled()
    }

    @Test
    fun untickingPublicOnAnItemThatWasPublicLeavesEveryCircleTicked() = runComposeUiTest {
        menu(item(ProfileAudience.Public), mutableListOf())

        onNodeWithTag("cards_sheet_public").performClick()

        circles.forEach { circle(it.id).assertIsOn().assertIsEnabled() }
    }

    @Test
    fun aRequiredItemKeepsPublicTickedAndDisabled() = runComposeUiTest {
        menu(item(ProfileAudience.Public, required = true), mutableListOf())

        onNodeWithTag("cards_sheet_public").assertIsOn().assertIsNotEnabled()
        circles.forEach { circle(it.id).assertIsOn().assertIsNotEnabled() }
    }

    @Test
    fun doneWithAnUnchangedDraftDismissesWithoutApplying() = runComposeUiTest {
        val applied = mutableListOf<ProfileAudience>()
        val dismissed = mutableListOf<Unit>()
        menu(item(ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID))), applied, dismissed)

        onNodeWithTag("cards_sheet_done").performClick()
        waitUntil { dismissed.isNotEmpty() }

        assertEquals(emptyList(), applied)
    }

    @Test
    fun cancelDiscardsATouchedDraft() = runComposeUiTest {
        val applied = mutableListOf<ProfileAudience>()
        val dismissed = mutableListOf<Unit>()
        menu(item(ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID))), applied, dismissed)

        circle(FAMILY_CIRCLE_ID).performClick()
        onNodeWithTag("cards_sheet_cancel").performClick()
        waitUntil { dismissed.isNotEmpty() }

        assertEquals(emptyList(), applied)
    }

    @Test
    fun doneWithNoCirclesAndEveryConnectionStoredCallsNothing() = runComposeUiTest {
        val applied = mutableListOf<ProfileAudience>()
        menu(item(ProfileAudience.Circles(emptySet(), emptySet())), applied, menuCircles = emptyList())

        onNodeWithTag("cards_sheet_done").performClick()
        waitForIdle()

        assertEquals(emptyList(), applied)
    }

    @Test
    fun doneAppliesTheDraftAndKeepsCirclesTheMenuDoesNotShow() = runComposeUiTest {
        val applied = mutableListOf<ProfileAudience>()
        menu(item(ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID), setOf("other-app"))), applied)

        circle(FAMILY_CIRCLE_ID).performClick()
        onNodeWithTag("cards_sheet_done").performClick()
        waitUntil { applied.isNotEmpty() }

        assertEquals(listOf<ProfileAudience>(ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID, FAMILY_CIRCLE_ID), setOf("other-app"))), applied)
    }

    @Test
    fun theCardsButtonIsGoneWhenTheCardFeatureIsOff() {
        fun phone() = ProfileAttribute(
            id = Uuid.random(),
            type = ProfileAttributeTypes.PHONE,
            versionTag = Uuid.random(),
            visibility = ProfileVisibility.OWNER,
            data = JsonObject(mapOf(ProfileAttributeTypes.KEY_PHONE to JsonPrimitive("+14155550123"))),
            acl = AccessControlList(requiredSecurityGroup = ProfileVisibility.OWNER.wireValue),
        )
        fun ComposeUiTest.editor(showCardsButton: Boolean) = setContent {
            MaterialTheme {
                CardContentEditor(
                    card = CardAudience.Public,
                    items = cardContentItems(listOf(phone()), circles, CardAudience.Public),
                    circles = circles,
                    photo = CardPhotoUi(photo = null, own = false, canChange = true, busy = false),
                    enabled = true,
                    importSources = emptyList(),
                    showCardsButton = showCardsButton,
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
        runComposeUiTest {
            editor(showCardsButton = false)
            onNodeWithTag("cards_button").assertDoesNotExist()
        }
        runComposeUiTest {
            editor(showCardsButton = true)
            onNodeWithTag("cards_button").assertExists()
        }
    }
}
