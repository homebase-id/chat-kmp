package id.homebase.core.ui.screens.contactbook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import id.homebase.chat.calling.history.CallsTabLabel
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ContactTabRowScreenshotTest {
    private val outDir = File("build/contact-tab-shots").apply { mkdirs() }

    private fun ComposeUiTest.row(widthDp: Int, dark: Boolean = false) = setContent {
        HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
            Box(Modifier.width(widthDp.dp)) {
                ContactTabRow(
                    tabs = contactTabs(callsAvailable = true),
                    selected = ContactTab.CALLS,
                    onSelect = {},
                    waiting = 3,
                    hasEnrollmentCandidates = true,
                    callsLabel = { CallsTabLabel(missedCount = 2) },
                )
            }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
    }

    @Test
    fun fourTabsFitAPhoneWithoutCuttingALabel() {
        var natural = 0f
        runDesktopComposeUiTest(800, 100) {
            setContent { HomebaseTheme(darkTheme = false, followsSystemTheme = false) { CallsTabLabel(missedCount = 2) } }
            natural = onNodeWithText("Calls", useUnmergedTree = true).getUnclippedBoundsInRoot().width.value
        }
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(360, 100) {
                row(360, dark)
                listOf("Contacts", "Calls", "New", "Circles", "Beta", "2", "3").forEach { onNodeWithText(it, useUnmergedTree = true).assertExists() }
                val calls = onNodeWithText("Calls", useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertTrue(calls.width.value >= natural - 0.5f, "the Calls label was squeezed to ${calls.width.value} from $natural")
                assertTrue(onNodeWithText("2", useUnmergedTree = true).getUnclippedBoundsInRoot().right.value <= 360f)
                save(if (dark) "tabs-360-dark" else "tabs-360")
            }
        }
    }
}
