package id.homebase.chat.widget

import id.homebase.core.ui.assets.Filesync
import id.homebase.core.ui.assets.HomebaseIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import id.homebase.chat.poll.PollDescriptor
import id.homebase.chat.services.content.MessageContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the conversation-list preview labels for typed message kinds (bug: a poll/dice/event
 * showed plain text with no kind icon). [typedMessageContentLabel] returns the kind icon plus
 * its [MessageContent.displayLabel] (the poll question, event title, …).
 */
class TypedMessageContentLabelTest {

    @Test
    fun poll_showsQuestionAndPollIcon() {
        val poll = MessageContent.Poll(
            PollDescriptor(question = "Pizza or sushi?", options = listOf("Pizza", "Sushi")),
        )
        val label = typedMessageContentLabel(poll)
        assertEquals("Pizza or sushi?", label?.text)
        assertEquals(Icons.Default.BarChart, label?.icon)
    }

    @Test
    fun event_dice_groodle_useTheirKindIcon() {
        assertEquals(Icons.Default.Event, typedMessageContentLabel(MessageContent.Event(null))?.icon)
        assertEquals(Icons.Default.Casino, typedMessageContentLabel(MessageContent.DiceRoll(null))?.icon)
        assertEquals(Icons.Default.CalendarMonth, typedMessageContentLabel(MessageContent.Groodle(null))?.icon)
    }

    @Test
    fun unparseable_fallsBackToKindLabel_keepingIcon() {
        val label = typedMessageContentLabel(MessageContent.Poll(null))
        assertEquals(MessageContent.UNPARSEABLE_POLL_LABEL, label?.text)
        assertEquals(Icons.Default.BarChart, label?.icon)
    }

    @Test
    fun agentUi_and_action_useTheirKindIcons() {
        val card = MessageContent.AgentUi(id.homebase.chat.agentui.AgentUiDescriptor(fallback = "Draft", title = "Draft post"))
        assertEquals("Draft post", typedMessageContentLabel(card)?.text)
        assertEquals(Icons.Default.SmartToy, typedMessageContentLabel(card)?.icon)
        assertEquals(MessageContent.UNPARSEABLE_AGENT_UI_LABEL, typedMessageContentLabel(MessageContent.AgentUi(null))?.text)
        assertEquals(Icons.Default.TouchApp, typedMessageContentLabel(MessageContent.AgentUiAction(null))?.icon)
        assertEquals(MessageContent.UNPARSEABLE_AGENT_ACTION_LABEL, typedMessageContentLabel(MessageContent.AgentUiAction(null))?.text)
    }

    @Test
    fun unknownKind_showsHelpIcon() {
        val label = typedMessageContentLabel(MessageContent.Unknown(dataType = 999))
        assertEquals(MessageContent.UNKNOWN_LABEL, label?.text)
        assertEquals(Icons.AutoMirrored.Outlined.HelpOutline, label?.icon)
    }

    @Test
    fun viewOnce_isLabelledByMessageContentLabelNotHere() {
        assertNull(typedMessageContentLabel(MessageContent.ViewOnce(id.homebase.chat.viewonce.ViewOnceDescriptor("video"))))
        assertNull(typedMessageContentLabel(MessageContent.ViewOnce(null)))
    }

    @Test
    fun fileShare_showsTheFilesyncLogo() {
        val label = typedMessageContentLabel(MessageContent.FileShare(null))
        assertEquals(MessageContent.UNPARSEABLE_FILE_SHARE_LABEL, label?.text)
        assertEquals(HomebaseIcons.Filesync, label?.icon)
    }

    @Test
    fun nullContent_returnsNull() {
        assertNull(typedMessageContentLabel(null))
    }
}
