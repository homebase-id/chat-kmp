package id.homebase.core.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class RoundedIconTest {

    @Test
    fun roundedIconWithoutOnClickHasNoClickAction() = runComposeUiTest {
        setContent { RoundedIcon(modifier = Modifier.testTag(TAG), imageVector = Icons.Filled.Edit) }
        onNodeWithTag(TAG).assertHasNoClickAction()
    }

    @Test
    fun roundedIconWithOnClickFiresIt() = runComposeUiTest {
        var clicks = 0
        setContent {
            RoundedIcon(modifier = Modifier.testTag(TAG), imageVector = Icons.Filled.Edit, onClick = { clicks++ })
        }
        onNodeWithTag(TAG).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun roundedIconFromFileWithoutOnClickHasNoClickAction() = runComposeUiTest {
        setContent { WithImageLoader { RoundedIconFromFile(modifier = Modifier.testTag(TAG), path = PATH) } }
        onNodeWithTag(TAG).assertHasNoClickAction()
    }

    @Test
    fun roundedIconFromFileWithOnClickFiresIt() = runComposeUiTest {
        var clicks = 0
        setContent {
            WithImageLoader {
                RoundedIconFromFile(modifier = Modifier.testTag(TAG), path = PATH, onClick = { clicks++ })
            }
        }
        onNodeWithTag(TAG).performClick()
        assertEquals(1, clicks)
    }

    @Composable
    private fun WithImageLoader(content: @Composable () -> Unit) {
        val context = LocalPlatformContext.current
        val koin = remember { koinApplication { modules(module { single { ImageLoader(context) } }) } }
        KoinIsolatedContext(koin) { content() }
    }

    private companion object {
        const val TAG = "rounded-icon"
        const val PATH = "missing.png"
    }
}
