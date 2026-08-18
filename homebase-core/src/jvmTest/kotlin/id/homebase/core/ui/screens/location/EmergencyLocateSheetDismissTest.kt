package id.homebase.core.ui.screens.location

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Phone-width only: `ModalBottomSheet` hides itself *before* it asks whether it may, so a caller
 * that refuses inside `onDismissRequest` keeps a sheet that has already animated away, over a
 * dialog window that still eats every touch. The wide branch is a plain `Dialog` and cannot
 * reach that state, so these run at a forced compact size.
 */
@OptIn(ExperimentalTestApi::class)
class EmergencyLocateSheetDismissTest {

    private val compactPhone = Size(400f, 800f)

    private val title = "Emergency location request"

    private val contact = ContactUiModel(
        id = Uuid.random(),
        odinId = OdinId("ada.example.com"),
        name = "Ada Vance",
        avatarInitials = "AV",
    )

    private fun SkikoComposeUiTest.showSheet(submitting: Boolean, onDismiss: () -> Unit) {
        setContent {
            MaterialTheme {
                EmergencyLocateSheet(
                    contact = contact,
                    lastPointAgeMs = null,
                    submitting = submitting,
                    onDismiss = onDismiss,
                    onConfirm = { _, _, _ -> },
                )
            }
        }
        waitForIdle()
    }

    /** The scrim above the sheet, in the sheet's own window — the last root the scene put up. */
    private fun SkikoComposeUiTest.tapAboveTheSheet() {
        onAllNodes(isRoot()).onLast().performTouchInput {
            click(Offset(width / 2f, 4f))
        }
        waitForIdle()
    }

    @Test
    fun `a tap outside mid-submit leaves the sheet on screen`() =
        runSkikoComposeUiTest(size = compactPhone) {
            var dismissals = 0
            showSheet(submitting = true) { dismissals++ }

            onNodeWithText(title).assertIsDisplayed()
            tapAboveTheSheet()

            onNodeWithText(title).assertIsDisplayed()
            assertEquals(0, dismissals)
        }

    @Test
    fun `a tap outside while idle dismisses`() =
        runSkikoComposeUiTest(size = compactPhone) {
            var dismissals = 0
            showSheet(submitting = false) { dismissals++ }

            tapAboveTheSheet()

            assertEquals(1, dismissals)
        }
}
