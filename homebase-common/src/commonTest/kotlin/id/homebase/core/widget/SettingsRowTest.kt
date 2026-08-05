package id.homebase.core.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsRowTest {

    @Test
    fun displaysTitle() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsRow(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    action = SettingsRowAction.Navigate {},
                )
            }
        }
        onNodeWithText("Notifications").assertExists()
    }

    @Test
    fun displaysSupportingText() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsRow(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    supportingText = "Push notifications are on",
                    action = SettingsRowAction.Navigate {},
                )
            }
        }
        onNodeWithText("Push notifications are on").assertExists()
    }

    @Test
    fun navigateRowFiresOnClick() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                SettingsRow(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    action = SettingsRowAction.Navigate { clicked = true },
                )
            }
        }
        onNodeWithText("Notifications").performClick()
        assertTrue(clicked)
    }

    @Test
    fun externalRowFiresOnClick() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                SettingsRow(
                    icon = Icons.Filled.Notifications,
                    title = "Security",
                    action = SettingsRowAction.External { clicked = true },
                )
            }
        }
        onNodeWithText("Security").performClick()
        assertTrue(clicked)
    }

    /**
     * The external hint rides the row's click label, so it can't be read out between the
     * headline and the supporting text the way a trailing contentDescription would be.
     */
    @Test
    fun externalRowAnnouncesItsHintAsAClickLabel() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsRow(
                    icon = Icons.Filled.Notifications,
                    title = "Delete",
                    supportingText = "Permanent",
                    action = SettingsRowAction.External {},
                    isDestructive = true,
                )
            }
        }
        onNodeWithText("Delete").assert(
            SemanticsMatcher("has an 'Open externally' click label") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "Open externally"
            }
        )
    }

    @Test
    fun destructiveRowKeepsTheAffordanceItsActionImplies() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                SettingsRow(
                    icon = Icons.Filled.Notifications,
                    title = "Delete",
                    action = SettingsRowAction.External { clicked = true },
                    isDestructive = true,
                )
            }
        }
        onNodeWithText("Delete").performClick()
        assertTrue(clicked)
    }

    @Test
    fun invokeRowFiresOnClick() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                SettingsRow(
                    icon = Icons.Filled.Notifications,
                    title = "Log out",
                    action = SettingsRowAction.Invoke { clicked = true },
                )
            }
        }
        onNodeWithText("Log out").performClick()
        assertTrue(clicked)
    }

    /** The row is the switch: one focusable node, carrying both the label and the state. */
    @Test
    fun toggleRowIsTheOnlyToggleableNodeAndReportsItsState() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsRow(
                    icon = Icons.Filled.Notifications,
                    title = "Show icon",
                    action = SettingsRowAction.Toggle(checked = true, onCheckedChange = {}),
                )
            }
        }
        onAllNodes(isToggleable()).assertCountEquals(1)
        onNodeWithText("Show icon").assertIsOn()
    }

    @Test
    fun toggleRowReportsOffState() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsRow(
                    icon = Icons.Filled.Notifications,
                    title = "Show icon",
                    action = SettingsRowAction.Toggle(checked = false, onCheckedChange = {}),
                )
            }
        }
        onAllNodes(isToggleable()).assertCountEquals(1)
        onNodeWithText("Show icon").assertIsOff()
    }

    @Test
    fun navigateRowIsNotToggleable() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsRow(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    action = SettingsRowAction.Navigate {},
                )
            }
        }
        onAllNodes(isToggleable()).assertCountEquals(0)
    }

    @Test
    fun tappingToggleRowBodyFlipsTheValue() = runComposeUiTest {
        var received: Boolean? = null
        setContent {
            MaterialTheme {
                SettingsRow(
                    icon = Icons.Filled.Notifications,
                    title = "Show icon",
                    action = SettingsRowAction.Toggle(
                        checked = false,
                        onCheckedChange = { received = it },
                    ),
                )
            }
        }
        onNodeWithText("Show icon").performClick()
        assertEquals(true, received)
    }

    @Test
    fun showsStatusSlotAlongsideTheAffordance() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsRow(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    action = SettingsRowAction.Navigate {},
                    status = { Text("OK") },
                )
            }
        }
        onNodeWithText("Notifications").assertExists()
        onNodeWithText("OK").assertExists()
    }
}
