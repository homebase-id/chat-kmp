@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The dialog a save runs into when the card collides with a contact you already have. It is reached
 * by surprise, so its three exits have to be exactly what they say: Cancel abandons the save,
 * "Add anyway" proceeds to the editor, and "View contact" leaves for the existing entry.
 */
@OptIn(ExperimentalTestApi::class)
class DuplicateContactDialogTest {

    private val match = ContactBookEntry(
        uniqueId = Uuid.random(),
        fileId = Uuid.random(),
        versionTag = Uuid.random(),
        odinId = "ada.example.com",
        displayName = "Ada Vance",
    )

    private class Exits {
        var dismissed = false
        var addedAnyway = false
        var opened: Pair<Uuid, String?>? = null
    }

    private fun ComposeUiTest.render(exits: Exits) = setContent {
        MaterialTheme {
            DuplicateContactDialog(
                match = match,
                onAddAnyway = { exits.addedAnyway = true },
                onOpenContact = { uniqueId, odinId -> exits.opened = uniqueId to odinId },
                onDismiss = { exits.dismissed = true },
            )
        }
    }

    @Test
    fun `the dismiss action reads Cancel and abandons the save`() = runComposeUiTest {
        val exits = Exits()
        render(exits)

        onNodeWithText("Cancel").assertIsDisplayed()
        onNodeWithText("Cancel").performClick()

        assertTrue(exits.dismissed, "Cancel must dismiss.")
        assertFalse(exits.addedAnyway, "Cancel must not fall through to the editor.")
    }

    @Test
    fun `Add anyway proceeds to the editor without dismissing`() = runComposeUiTest {
        val exits = Exits()
        render(exits)

        onNodeWithText("Add anyway").performClick()

        assertTrue(exits.addedAnyway)
        assertFalse(exits.dismissed, "Proceeding must keep the flow mounted.")
    }

    @Test
    fun `View contact closes this dialog before navigating away`() = runComposeUiTest {
        val exits = Exits()
        render(exits)

        onNodeWithText("View contact").performClick()

        assertTrue(exits.dismissed, "The host is above the nav graph; it must close first.")
        assertEquals(match.uniqueId to match.odinId, exits.opened)
    }

    @Test
    fun `the body names the contact that already holds one of these values`() = runComposeUiTest {
        render(Exits())

        onNodeWithText("Ada Vance", substring = true).assertIsDisplayed()
    }
}
