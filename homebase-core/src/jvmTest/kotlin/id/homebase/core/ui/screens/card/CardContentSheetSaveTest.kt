@file:OptIn(ExperimentalTestApi::class, ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.ui.screens.profile.ProfileAudience
import id.homebase.resources.MR
import id.homebase.resources.profile_card_sheet_not_on_card
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CardContentSheetSaveTest {

    private val circles = listOf(CardCircle(FRIENDS_CIRCLE_ID, "Friends"), CardCircle(FAMILY_CIRCLE_ID, "Family"))
    private val card = CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends")

    private fun item(type: String, data: Map<String, String>, audience: ProfileAudience = ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID))) =
        CardContentItem(id = Uuid.random(), type = type, text = data.values.first(), audience = audience, shown = true, locked = false, data = data)

    private class Saved {
        var updates: Map<String, String>? = null
        var audience: ProfileAudience? = null
    }

    private fun androidx.compose.ui.test.ComposeUiTest.open(item: CardContentItem, saved: Saved = Saved()) {
        setContent {
            MaterialTheme {
                ContentSheet(
                    type = item.type,
                    card = card,
                    circles = circles,
                    editing = item,
                    onDismiss = {},
                    onEdit = { updates, audience -> saved.updates = updates; saved.audience = audience },
                )
            }
        }
    }

    @Test
    fun anUntouchedItemOffersNoSave() = runComposeUiTest {
        open(item(ProfileAttributeTypes.DISCORD, mapOf(ProfileAttributeTypes.KEY_DISCORD to "maniac_fighter")))
        onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun anUntouchedStoredPhoneWithSpacesOffersNoSave() = runComposeUiTest {
        open(item(ProfileAttributeTypes.PHONE, mapOf(ProfileAttributeTypes.KEY_PHONE to "+91 965 436 7034")))
        waitForIdle()
        onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun changingOnlyTheAudienceEnablesSave() = runComposeUiTest {
        open(item(ProfileAttributeTypes.DISCORD, mapOf(ProfileAttributeTypes.KEY_DISCORD to "maniac_fighter")))
        onNodeWithTag("audience_pill_Family").performClick()
        onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun changingTheTextEnablesSaveAndChangingItBackDisablesIt() = runComposeUiTest {
        open(item(ProfileAttributeTypes.DISCORD, mapOf(ProfileAttributeTypes.KEY_DISCORD to "maniac_fighter")))
        val field = onNode(hasSetTextAction())
        field.performTextInput("x")
        onNodeWithText("Save").assertIsEnabled()
        field.performTextClearance()
        field.performTextInput("maniac_fighter")
        onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun aStoredPhoneWithSpacesSavesAnAudienceOnlyChangeAsE164() = runComposeUiTest {
        val saved = Saved()
        open(item(ProfileAttributeTypes.PHONE, mapOf(ProfileAttributeTypes.KEY_PHONE to "+91 965 436 7034")), saved)
        waitForIdle()
        onNodeWithTag("audience_pill_Family").performClick()
        onNodeWithText("Save").assertIsEnabled()
        onNodeWithText("Save").performClick()
        waitForIdle()
        assertEquals("+919654367034", saved.updates?.get(ProfileAttributeTypes.KEY_PHONE))
    }

    @Test
    fun editingTheDigitsOfAnExistingPhoneEnablesSave() = runComposeUiTest {
        open(item(ProfileAttributeTypes.PHONE, mapOf(ProfileAttributeTypes.KEY_PHONE to "+919654367034")))
        waitForIdle()
        val field = onNode(hasSetTextAction())
        field.performTextClearance()
        field.performTextInput("9654367035")
        onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun editingTheDigitsOfAnExistingPhoneStoredWithSpacesEnablesSave() = runComposeUiTest {
        open(item(ProfileAttributeTypes.PHONE, mapOf(ProfileAttributeTypes.KEY_PHONE to "+49 151 2345 6789")))
        waitForIdle()
        val field = onNode(hasSetTextAction())
        field.performTextClearance()
        field.performTextInput("15123456780")
        onNodeWithText("Save").assertIsEnabled()
    }

    private val notOnCard = runBlocking { getString(MR.string.profile_card_sheet_not_on_card) }

    @Test
    fun anExistingTypeTheCardCannotRenderSaysSoInItsSheet() = runComposeUiTest {
        open(item(ProfileAttributeTypes.DISCORD, mapOf(ProfileAttributeTypes.KEY_DISCORD to "maniac_fighter")))
        onNodeWithText(notOnCard).assertIsDisplayed()
    }

    @Test
    fun anExistingTypeTheCardRendersShowsNoNote() = runComposeUiTest {
        open(item(ProfileAttributeTypes.TWITTER, mapOf(ProfileAttributeTypes.KEY_TWITTER to "frodo")))
        onNodeWithText(notOnCard).assertDoesNotExist()
    }

    @Test
    fun addingATypeTheCardCannotRenderReplacesTheShownOnCardLine() = runComposeUiTest {
        setContent {
            MaterialTheme { ContentSheet(type = ProfileAttributeTypes.DISCORD, card = card, circles = circles, onDismiss = {}) }
        }
        onNodeWithText(notOnCard).assertIsDisplayed()
        onNode(hasText("Shown on the", substring = true)).assertDoesNotExist()
    }

    @Test
    fun addingATypeTheCardRendersKeepsTheShownOnCardLine() = runComposeUiTest {
        setContent {
            MaterialTheme { ContentSheet(type = ProfileAttributeTypes.TWITTER, card = card, circles = circles, onDismiss = {}) }
        }
        onNode(hasText("Shown on the", substring = true)).assertIsDisplayed()
        onNodeWithText(notOnCard).assertDoesNotExist()
    }
}
