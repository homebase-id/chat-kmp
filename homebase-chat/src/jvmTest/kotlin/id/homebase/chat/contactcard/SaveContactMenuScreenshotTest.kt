package id.homebase.chat.contactcard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import id.homebase.core.contacts.DeviceContactSaver
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/** Polish renders: the open Save menu anchored to its real button, in a conversation and on the detail screen. */
@OptIn(ExperimentalTestApi::class)
class SaveContactMenuScreenshotTest {
    private val outDir = File("../build/polish-shots").apply { mkdirs() }
    private val saver = DeviceContactSaver { true }

    private val card = ContactCardDescriptor(
        displayName = "Samwise Gamgee",
        organization = "Bag End Gardens",
        phones = listOf("+14155550123"),
        emails = listOf("sam@example.com"),
    )
    private val incoming = "Here's Sam's number, he'll sort the garden"
    private val outgoing = "Perfect, thanks!"
    private val sentAt = "10:42"
    private val chats = listOf("Frodo Baggins", "Samwise Gamgee", "Note to Self", "Rosie Cotton")

    // A popup window is not part of the captured root, so the menu is drawn in the root at the
    // spot DropdownMenu would place it: under the anchor's start edge, 4dp down.
    private var anchor by mutableStateOf<Rect?>(null)

    @Composable
    private fun MenuOverlay(alreadySaved: Boolean) {
        val at = anchor ?: return
        Surface(
            shape = SaveContactMenuShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = SaveContactMenuShadow,
            border = saveContactMenuBorder(),
            modifier = Modifier
                .offset { IntOffset(at.left.roundToInt(), (at.bottom + 4.dp.toPx()).roundToInt()) }
                .width(SaveContactMenuWidth),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                SaveContactMenuItems(alreadySaved, onHomebase = {}, onPhone = {})
            }
        }
    }

    @Composable
    private fun Message(text: String, mine: Boolean) {
        Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    @Composable
    private fun Conversation(modifier: Modifier) {
        Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.displayName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
            Message(incoming, mine = false)
            ContactCardBubble(
                descriptor = card,
                onSaveToContacts = { _, _ -> },
                deviceSaver = saver,
                footer = {
                    Text(sentAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
            Message(outgoing, mine = true)
        }
    }

    @Composable
    private fun ChatList() {
        Column(Modifier.width(360.dp).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            chats.forEach { name ->
                val selected = name == card.displayName
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(40.dp)) {}
                        Spacer(Modifier.width(12.dp))
                        Text(name, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    // The detail is a full-window dialog, so only the conversation sits beside the chat list.
    private fun ComposeUiTest.render(
        dark: Boolean,
        withList: Boolean,
        alreadySaved: Boolean,
        content: @Composable () -> Unit,
    ) {
        anchor = null
        setContent {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.fillMaxSize()) {
                        if (withList) {
                            Row {
                                ChatList()
                                VerticalDivider()
                                Box(Modifier.weight(1f)) { content() }
                            }
                        } else {
                            content()
                        }
                        MenuOverlay(alreadySaved)
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.openMenuUnder(label: String, alreadySaved: Boolean, name: String) {
        waitForIdle()
        val button = onAllNodes(hasClickAction() and hasAnyDescendant(hasText(label)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .minBy { it.boundsInRoot.width * it.boundsInRoot.height }
        anchor = button.boundsInRoot
        waitForIdle()
        val title = if (alreadySaved) "Update Homebase contact" else "Homebase contacts"
        assertTrue(onAllNodes(hasText(title), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodes(hasText("Phone contacts"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }

    @Test
    fun saveMenuRendersAtPhoneAndWideWidthsInBothThemes() {
        for (dark in listOf(false, true)) for (wide in listOf(false, true)) {
            val suffix = "${if (wide) "wide" else "phone"}-${if (dark) "dark" else "light"}"
            val (w, h) = if (wide) 1280 to 800 else 412 to 860

            // The bubble's update state needs a saved odinId, which fetches an avatar; the detail screen covers it.
            runDesktopComposeUiTest(w, h) {
                render(dark, withList = wide, alreadySaved = false) {
                    Conversation(Modifier.fillMaxSize())
                }
                openMenuUnder("Save to contacts", alreadySaved = false, name = "conversation-save-$suffix")
            }

            for (saved in listOf(false, true)) {
                runDesktopComposeUiTest(w, h) {
                    render(dark, withList = false, alreadySaved = saved) {
                        ContactCardDetailContent(
                            descriptor = card,
                            onDismiss = {},
                            onSaveToContacts = {},
                            alreadySaved = saved,
                            onMessageIdentity = null,
                            authorOdinId = null,
                            photo = null,
                            deviceSaver = saver,
                        )
                    }
                    val label = if (saved) "Update contact" else "Save to contacts"
                    openMenuUnder(label, saved, "detail-${if (saved) "update" else "save"}-$suffix")
                }
            }
        }
    }
}
