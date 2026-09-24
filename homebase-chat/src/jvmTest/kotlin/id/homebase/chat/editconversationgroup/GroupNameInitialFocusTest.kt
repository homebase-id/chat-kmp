package id.homebase.chat.editconversationgroup

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class GroupNameInitialFocusTest {

    @Test
    fun `the name field takes focus once loading finishes`() = runComposeUiTest {
        val uiState = mutableStateOf(EditConversationGroupUiState(isLoading = true))
        setContent {
            MaterialTheme {
                EditConversationGroupUi(
                    snackbarHostState = SnackbarHostState(),
                    uiState = uiState.value,
                    groupNameTextState = TextFieldState(),
                    onUiAction = {},
                )
            }
        }
        waitForIdle()

        uiState.value = uiState.value.copy(isLoading = false)
        waitForIdle()

        onNode(hasSetTextAction()).assertIsFocused()
    }
}
