package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.core.ui.theme.HomebaseTheme
import org.koin.compose.KoinIsolatedContext
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class MentionBadgeScreenshotTest {
    private val outDir = File("../build/polish-shots").apply { mkdirs() }
    private val mentionBadge = hasContentDescription("You were mentioned")

    private fun row(name: String, unread: Int, mentions: Int) = EnrichedConversationUiModel(
        conversation = ConversationUiModel(
            id = Uuid.random(),
            name = name,
            lastMessage = "@frodo.demo.rocks are you coming tonight?",
            latestMessageTimestamp = Instant.fromEpochMilliseconds(1_700_000_000_000),
            unreadCount = unread,
            unreadMentionCount = mentions,
            avatarInitials = name.take(2),
            avatarTiny = null,
            lastRead = Instant.fromEpochMilliseconds(0),
            avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback, isGroup = true),
            admins = emptySet(),
            isGroup = true,
            participants = listOf(OdinId("sam.demo.rocks"), OdinId("merry.demo.rocks")),
        ),
        participants = emptyList(),
        missingConnections = emptyList(),
    )

    private fun ComposeUiTest.show(dark: Boolean, wide: Boolean) = setContent {
        KoinIsolatedContext(OfflineAvatarKoin) {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    // Wide windows show the list as the scaffold's 360dp list pane beside the chat.
                    Box(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxSize()) {
                            Column(if (wide) Modifier.width(360.dp) else Modifier.weight(1f)) {
                                listOf(
                                    row("Fellowship", unread = 4, mentions = 2),
                                    row("Shire Council", unread = 1, mentions = 1),
                                    row("Green Dragon", unread = 3, mentions = 0),
                                    row("Rivendell", unread = 0, mentions = 0),
                                ).forEach {
                                    ConversationItem(
                                        enrichedData = it,
                                        onClick = {},
                                        onArchiveClick = {},
                                        onContactClick = {},
                                        isSelected = false,
                                    )
                                }
                            }
                            if (wide) VerticalDivider()
                        }
                        Column(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            JumpToMentionButton(visible = true, count = 2, onClick = {})
                            ScrollToBottomButton(visible = true, unreadCount = 4, onClick = {})
                        }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        waitForIdle()
        assertEquals(2, onAllNodes(mentionBadge, useUnmergedTree = true).fetchSemanticsNodes().size)
        onNode(hasContentDescription("Jump to unread mention")).assertExists()
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        val colors = HashSet<Int>()
        for (x in 0 until image.width step 7) for (y in 0 until image.height step 7) colors += image.getRGB(x, y)
        assertTrue(colors.size > 8, "$name rendered blank")
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }

    @Test
    fun aLoneMentionShowsItsCount() {
        for ((count, badges) in listOf(1 to 1, 2 to 1)) runDesktopComposeUiTest(200, 200) {
            setContent { HomebaseTheme { JumpToMentionButton(visible = true, count = count, onClick = {}) } }
            waitForIdle()
            assertEquals(badges, onAllNodes(hasText("$count"), useUnmergedTree = true).fetchSemanticsNodes().size, "count=$count")
        }
    }

    @Test
    fun rendersEveryWidthAndTheme() {
        runDesktopComposeUiTest(411, 860) { show(dark = false, wide = false); save("mention-phone-light") }
        runDesktopComposeUiTest(411, 860) { show(dark = true, wide = false); save("mention-phone-dark") }
        runDesktopComposeUiTest(1280, 800) { show(dark = false, wide = true); save("mention-wide-light") }
        runDesktopComposeUiTest(1280, 800) { show(dark = true, wide = true); save("mention-wide-dark") }
    }
}
