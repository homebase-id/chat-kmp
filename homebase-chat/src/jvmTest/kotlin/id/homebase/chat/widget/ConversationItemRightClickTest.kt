package id.homebase.chat.widget

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.core.ui.theme.HomebaseTheme
import org.koin.compose.KoinIsolatedContext
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class ConversationItemRightClickTest {
    private val row = EnrichedConversationUiModel(
        conversation = ConversationUiModel(
            id = Uuid.random(),
            name = "Fellowship",
            lastMessage = "Second breakfast?",
            latestMessageTimestamp = Instant.fromEpochMilliseconds(1_700_000_000_000),
            unreadCount = 0,
            unreadMentionCount = 0,
            avatarInitials = "Fe",
            avatarTiny = null,
            lastRead = Instant.fromEpochMilliseconds(0),
            avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback, isGroup = true),
            admins = emptySet(),
            isGroup = true,
            participants = listOf(OdinId("sam.demo.rocks")),
        ),
        participants = emptyList(),
        missingConnections = emptyList(),
    )

    @Test
    fun right_click_opens_the_row_menu() = runComposeUiTest {
        setContent {
            KoinIsolatedContext(OfflineAvatarKoin) {
                HomebaseTheme(darkTheme = false) {
                    ConversationItem(
                        enrichedData = row,
                        onClick = {},
                        onArchiveClick = {},
                        onContactClick = {},
                        isSelected = false,
                    )
                }
            }
        }
        onAllNodes(hasText("Archive")).assertCountEquals(0)
        onNodeWithText("Fellowship").performMouseInput { rightClick() }
        waitForIdle()
        onAllNodes(hasText("Archive")).assertCountEquals(1)
    }
}
