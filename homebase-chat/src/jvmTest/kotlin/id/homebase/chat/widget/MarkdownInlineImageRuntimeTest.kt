package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

// mikepenz reads Painter.$stable once a transformer hands it a painter; that field is absent in Compose 1.10.
@OptIn(ExperimentalTestApi::class)
class MarkdownInlineImageRuntimeTest {

    @Test
    fun rendererInlineImageWithPainterComposes() = runComposeUiTest {
        val transforms = AtomicInteger()
        val transformer = object : ImageTransformer {
            @Composable
            override fun transform(link: String): ImageData {
                transforms.incrementAndGet()
                return ImageData(ColorPainter(Color.Red))
            }
        }
        setContent {
            Box(Modifier.testTag("md")) {
                Markdown(content = "see ![cat](https://example.test/cat.png) here", imageTransformer = transformer)
            }
        }
        waitUntil(timeoutMillis = 5_000) { transforms.get() > 0 }
        waitForIdle()
        assertTrue(transforms.get() > 0)
    }

    @Test
    fun chatMarkdownInlineImageInsideTextRenders() = runComposeUiTest {
        setContent {
            Box(Modifier.testTag("md")) {
                ChatMarkdown(content = "see ![cat](https://example.test/cat.png) here")
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("cat", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        waitForIdle()
    }
}
