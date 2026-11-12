package id.homebase.chat.contactcard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.core.contacts.DeviceContact
import id.homebase.core.contacts.DeviceContactSaver
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class SaveContactMenuButtonTest {

    private val card = ContactCardDescriptor(
        displayName = "Ada Vance",
        phones = listOf("+14155550123", "+4512345678"),
        emails = listOf("ada@example.com"),
    )

    @Test
    fun `with a saver, Save opens both items and each calls its own callback`() = runComposeUiTest {
        var homebase: Pair<ContactCardDescriptor, Boolean>? = null
        var opened: DeviceContact? = null
        setContent {
            HomebaseTheme(darkTheme = false) {
                SaveContactMenuButton(
                    descriptor = card,
                    alreadySaved = false,
                    onSaveToContacts = { c, saved -> homebase = c to saved },
                    deviceSaver = DeviceContactSaver { opened = it; true },
                )
            }
        }

        onNodeWithText("Save to contacts").performClick()
        onNodeWithText("Homebase contacts").assertIsDisplayed()
        onNodeWithText("Phone contacts").assertIsDisplayed()
        assertNull(homebase)

        onNodeWithText("Homebase contacts").performClick()
        assertEquals(card to false, homebase)
        assertNull(opened)

        onNodeWithText("Save to contacts").performClick()
        onNodeWithText("Phone contacts").performClick()
        assertEquals(card.toDeviceContact(), opened)
    }

    @Test
    fun `an already saved card offers the update label in the menu`() = runComposeUiTest {
        setContent {
            HomebaseTheme(darkTheme = false) {
                SaveContactMenuButton(
                    descriptor = card,
                    alreadySaved = true,
                    onSaveToContacts = { _, _ -> },
                    deviceSaver = DeviceContactSaver { true },
                )
            }
        }

        onNodeWithText("Update contact").performClick()
        onNodeWithText("Update Homebase contact").assertIsDisplayed()
        onNodeWithText("Phone contacts").assertIsDisplayed()
    }

    @Test
    fun `without a saver, Save goes straight to Homebase`() = runComposeUiTest {
        var homebase: Pair<ContactCardDescriptor, Boolean>? = null
        setContent {
            HomebaseTheme(darkTheme = false) {
                SaveContactMenuButton(
                    descriptor = card,
                    alreadySaved = false,
                    onSaveToContacts = { c, saved -> homebase = c to saved },
                    deviceSaver = null,
                )
            }
        }

        onNodeWithText("Save to contacts").performClick()

        assertEquals(card to false, homebase)
        onNodeWithText("Phone contacts").assertDoesNotExist()
    }
}
