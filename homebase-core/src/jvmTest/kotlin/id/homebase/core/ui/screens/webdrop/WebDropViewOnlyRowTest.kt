package id.homebase.core.ui.screens.webdrop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.ui.screens.webdrop.components.WebDropComposeSheet
import kotlin.test.Test
import kotlin.test.assertEquals
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module

@OptIn(ExperimentalTestApi::class)
class WebDropViewOnlyRowTest {

    private val koin = koinApplication {
        modules(module { single<FileOperationsProvider> { StageFakeFileOps(mutableMapOf()) } })
    }

    @Test
    fun tappingViewOnlyInTheComposeSheetEmitsViewOnlyToggled() = runComposeUiTest {
        val actions = mutableListOf<WebDropUiAction>()
        setContent {
            KoinIsolatedContext(koin) {
                MaterialTheme {
                    WebDropComposeSheet(
                        uiState = WebDropUiState(composeOpen = true),
                        onAction = { actions += it },
                    )
                }
            }
        }

        onNodeWithText("View only").performClick()
        waitForIdle()
        assertEquals(listOf<WebDropUiAction>(WebDropUiAction.ViewOnlyToggled(true)), actions)
    }
}
