package id.homebase.chat.widget

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.conversationlist.RecipientGroupModel
import id.homebase.chat.conversationlist.RecipientModel
import id.homebase.chat.conversationlist.RecipientType
import id.homebase.chat.conversationlist.SendToPicker
import id.homebase.chat.data.ContactUiModel
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.initials
import kotlinx.collections.immutable.persistentListOf
import org.koin.compose.KoinIsolatedContext
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class SendToScreenScreenshotTest {
    private val outDir = File("../build/polish-shots").apply { mkdirs() }

    private fun contact(name: String) = RecipientModel.Contact(
        ContactUiModel(
            id = Uuid.random(),
            odinId = OdinId("${name.lowercase().replace(' ', '.')}.demo.rocks"),
            name = name,
            avatarInitials = name.initials(),
        ),
    )

    private val frodo = contact("Frodo Baggins")
    private val sam = contact("Samwise Gamgee")
    private val merry = contact("Merry Brandybuck")
    private val pippin = contact("Pippin Took")

    private fun picker(selected: List<RecipientModel>) = SendToPicker(
        caption = "",
        viewOnce = false,
        recipients = persistentListOf(
            RecipientGroupModel(RecipientType.Recents, listOf(frodo, sam)),
            RecipientGroupModel(RecipientType.Contacts, listOf(merry, pippin)),
        ),
        selected = persistentListOf<RecipientModel>().addAll(selected),
    )

    private fun ComposeUiTest.show(
        selected: List<RecipientModel>,
        dark: Boolean,
        actions: MutableList<ConversationListUiAction> = mutableListOf(),
    ) = setContent {
        KoinIsolatedContext(OfflineAvatarKoin) {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                SendToScreen(picker = picker(selected), sending = false, onUiAction = { actions += it })
            }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        waitForIdle()
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        val colors = HashSet<Int>()
        for (x in 0 until image.width step 7) for (y in 0 until image.height step 7) colors += image.getRGB(x, y)
        assertTrue(colors.size > 8, "$name rendered blank")
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }

    @Test
    fun sendBarAppearsWithSelectionAndSends() = runDesktopComposeUiTest(411, 860) {
        val actions = mutableListOf<ConversationListUiAction>()
        show(selected = listOf(frodo, sam), dark = false, actions = actions)
        save("sendto-phone-light")
        onNodeWithContentDescription("Send message").performClick()
        assertEquals(ConversationListUiAction.ConfirmSendTo, actions.single())
    }

    @Test
    fun rendersEveryWidthAndTheme() {
        runDesktopComposeUiTest(411, 860) { show(selected = emptyList(), dark = false); save("sendto-phone-empty-light") }
        runDesktopComposeUiTest(411, 860) { show(selected = listOf(frodo, sam), dark = true); save("sendto-phone-dark") }
        runDesktopComposeUiTest(1280, 800) { show(selected = listOf(frodo, merry), dark = false); save("sendto-wide-light") }
        runDesktopComposeUiTest(1280, 800) { show(selected = listOf(frodo, merry), dark = true); save("sendto-wide-dark") }
    }
}
