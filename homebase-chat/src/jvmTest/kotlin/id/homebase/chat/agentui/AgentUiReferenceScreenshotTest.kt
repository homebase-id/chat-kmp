package id.homebase.chat.agentui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AgentUiReferenceScreenshotTest {
    private fun capture(dark: Boolean, name: String) = runComposeUiTest {
        val tree = AgentUiFixtures.decodeDraftPostCard()
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Box(Modifier.testTag("card").width(360.dp).padding(12.dp)) {
                        val inputs = remember { AgentUiInputState(tree.inputs) }
                        AgentUiTreeView(tree, inputs, { null }, true, emptySet(), {})
                    }
                }
            }
        }
        onNodeWithText("Approve").assertExists()
        onNodeWithText("LinkedIn").assertExists()
        val image = onNodeWithTag("card").captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
        val out = File("build/agent-ui-screenshots").apply { mkdirs() }
        ImageIO.write(image.toAwtImage(), "png", File(out, "$name.png"))
    }

    @Test fun reference_card_light() = capture(dark = false, name = "draft-post-light")

    @Test fun reference_card_dark() = capture(dark = true, name = "draft-post-dark")
}
