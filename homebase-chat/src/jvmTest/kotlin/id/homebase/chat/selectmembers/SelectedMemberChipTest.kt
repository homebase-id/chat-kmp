package id.homebase.chat.selectmembers

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import kotlinx.collections.immutable.persistentListOf
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SelectedMemberChipTest {

    private val koin = koinApplication {
        modules(
            module {
                single {
                    ImageLoader.Builder(PlatformContext.INSTANCE)
                        .components {
                            add(Interceptor { chain -> ErrorResult(null, chain.request, UnsupportedOperationException()) })
                        }
                        .build()
                }
            },
        )
    }

    @Test
    fun `tapping a selected member's chip removes them`() = runComposeUiTest {
        val alice = ContactUiModel.fallbackFor(OdinId("alice.example.com")).copy(name = "Alice")
        val actions = mutableListOf<SelectMembersUiAction>()
        setContent {
            KoinIsolatedContext(koin) {
                MaterialTheme {
                    SelectMembersUi(
                        snackbarHostState = SnackbarHostState(),
                        uiState = SelectMembersUiState(selectedContacts = persistentListOf(alice)),
                        searchTextState = TextFieldState(),
                        onUiAction = { actions += it },
                    )
                }
            }
        }

        onNodeWithText("Alice").performClick()

        assertEquals(listOf<SelectMembersUiAction>(SelectMembersUiAction.ContactClicked(alice)), actions)
    }
}
