package id.homebase.chat.contactcard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.core.contacts.DeviceContactSaver
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SaveContactMenuScreenshotTest {
    private val outDir = File("../build/polish-shots").apply { mkdirs() }

    private val card = ContactCardDescriptor(
        displayName = "Samwise Gamgee",
        phones = listOf("+14155550123"),
        emails = listOf("sam@example.com"),
    )

    // A popup window is not part of the captured root, so the open menu is drawn inline under the button.
    private fun ComposeUiTest.show(dark: Boolean, alreadySaved: Boolean) {
        setContent {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.padding(16.dp), contentAlignment = Alignment.TopStart) {
                        Column(
                            Modifier.widthIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(card.displayName, style = MaterialTheme.typography.titleMedium)
                                    SaveContactMenuButton(
                                        descriptor = card,
                                        alreadySaved = alreadySaved,
                                        onSaveToContacts = { _, _ -> },
                                        deviceSaver = DeviceContactSaver { true },
                                    )
                                }
                            }
                            Surface(
                                shape = SaveContactMenuShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shadowElevation = 3.dp,
                                modifier = Modifier.width(300.dp),
                            ) {
                                Column(Modifier.padding(vertical = 8.dp)) {
                                    SaveContactMenuItems(alreadySaved, onHomebase = {}, onPhone = {})
                                }
                            }
                            SaveContactMenuButton(
                                descriptor = card,
                                alreadySaved = alreadySaved,
                                onSaveToContacts = { _, _ -> },
                                filled = true,
                                deviceSaver = DeviceContactSaver { true },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        waitForIdle()
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }

    @Test
    fun saveMenuRendersAtPhoneAndWideWidthsInBothThemes() {
        for (dark in listOf(false, true)) for (saved in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            val state = if (saved) "update" else "save"
            for ((size, w, h) in listOf(Triple("phone", 412, 520), Triple("wide", 1280, 520))) {
                runDesktopComposeUiTest(w, h) {
                    show(dark, saved)
                    val title = if (saved) "Update Homebase contact" else "Homebase contacts"
                    assertTrue(onAllNodes(hasText(title), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
                    assertTrue(onAllNodes(hasText("Phone contacts"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
                    save("save-contact-$state-$size-$theme")
                }
            }
        }
    }
}
