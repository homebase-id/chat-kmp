@file:OptIn(ExperimentalTestApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

class CardAddSheetFocusTest {

    @Test
    fun theFirstCharacterTypedIntoANewStatusKeepsTheFieldFocusedAndTheSheetOpen() = runComposeUiTest {
        val card = CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends")
        setContent {
            MaterialTheme {
                CardContentEditor(
                    card = card,
                    items = cardContentItems(emptyList(), emptyList(), card),
                    circles = listOf(CardCircle(FRIENDS_CIRCLE_ID, "Friends")),
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
        onNodeWithTag("content_add").performClick()
        waitForIdle()
        onNodeWithContentDescription("Add new: More").performClick()
        waitForIdle()
        onNodeWithText("Status").performClick()
        waitForIdle()

        val field = onNode(hasSetTextAction())
        field.performClick()
        waitForIdle()
        field.assertIsFocused()
        field.performTextInput("a")
        waitForIdle()

        field.assertIsFocused()
        field.assertTextEquals("a")
        field.performTextInput("bc")
        waitForIdle()
        field.assertIsFocused()
        field.assertTextEquals("abc")
    }
}
