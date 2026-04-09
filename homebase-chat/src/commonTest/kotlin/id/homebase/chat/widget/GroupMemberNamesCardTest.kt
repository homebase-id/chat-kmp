package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.core.test.setTestLocale
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class GroupMemberNamesCardTest {

    @Test
    fun showsAllNames_whenFourOrFewer() = runComposeUiTest {
        setTestLocale("en-US")
        setContent {
            MaterialTheme {
                GroupMemberNamesCard(
                    participantNames = persistentListOf("Alice", "Bob"),
                )
            }
        }
        // String resource: "%1$s and you" → "Alice, Bob and you"
        onNodeWithText("Alice, Bob and you").assertExists()
    }

    @Test
    fun showsTruncatedNames_whenMoreThanFour() = runComposeUiTest {
        setTestLocale("en-US")
        setContent {
            MaterialTheme {
                GroupMemberNamesCard(
                    participantNames = persistentListOf("Alice", "Bob", "Charlie", "Dave", "Eve"),
                )
            }
        }
        // String resource: "%1$s, you and %2$s others" → "Alice, Bob, Charlie, you and 2 others"
        onNodeWithText("Alice, Bob, Charlie, you and 2 others").assertExists()
    }

    @Test
    fun showsSingleParticipant() = runComposeUiTest {
        setTestLocale("en-US")
        setContent {
            MaterialTheme {
                GroupMemberNamesCard(
                    participantNames = persistentListOf("Alice"),
                )
            }
        }
        onNodeWithText("Alice and you").assertExists()
    }
}
