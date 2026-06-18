package id.homebase.chat.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Casino
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
    fun unknownKind_showsHelpIcon() {
        val label = typedMessageContentLabel(MessageContent.Unknown(dataType = 999))
        assertEquals(MessageContent.UNKNOWN_LABEL, label?.text)
        assertEquals(Icons.AutoMirrored.Outlined.HelpOutline, label?.icon)
    }

    @Test
    fun nullContent_returnsNull() {
        assertNull(typedMessageContentLabel(null))
    }
}
