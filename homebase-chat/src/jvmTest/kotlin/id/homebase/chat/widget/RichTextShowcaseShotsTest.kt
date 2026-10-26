package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import id.homebase.chat.services.MessageAppData
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.applyMarkDownContent
import kotlinx.serialization.json.Json
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RichTextShowcaseShotsTest {

    private val legacyPlate = MessageAppData(
        message = Json.parseToJsonElement(
            """[{"type":"p","children":[{"text":"legacy ","bold":true},{"text":"plate message from frodo.example"}]}]""",
        ),
    ).getMessage()

    private val source = """
# Heading one

## Heading two

### Heading three

**bold** *italic* ~~strike~~ `code_span` and **_mix_** together

Auto https://example.test/auto and [labelled link](https://example.test/docs) here

![diagram](https://example.test/pic.png)

- first bullet
- second bullet
  - nested bullet
1. numbered one
2. numbered two

- [x] Done task
- [ ] Open task

> quoted line
>
> - nested in quote

```kotlin
fun main() {
    println("hello") // say hi
}
```

```
plain fence
```

| H1 | H2 | H3 | H4 | H5 | H6 |
|----|----|----|----|----|----|
| a1 | b1 | c1 | d1 | e1 | f1 |
| a2 | b2 | c2 | d2 | e2 | f2 |

hi @sam.example from frodo.example

Family 👨‍👩‍👧 party 🎉 costs ${'$'}5

$legacyPlate
""".trim()

    private val sections = listOf(
        "Heading one", "Heading two", "Heading three", "bold", "italic", "strike", "code_span", "mix",
        "https://example.test/auto", "labelled link", "diagram", "first bullet", "nested bullet", "numbered two",
        "Done task", "Open task", "quoted line", "nested in quote", "println", "plain fence", "H6", "f2",
        "@sam.example", "👨‍👩‍👧", "${'$'}5", "legacy", "plate message",
    )

    private val outDir: File = run {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        File(dir ?: File(System.getProperty("user.dir")), "build/richtext-shots").also { it.mkdirs() }
    }

    private fun ComposeUiTest.allText(): String =
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { n ->
                n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                    listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text)
            }
            .joinToString("\n")

    private fun ComposeUiTest.assertShowcase() {
        waitUntil(timeoutMillis = 10_000) { "plate message" in allText() }
        val text = allText()
        for (s in sections) assertTrue(s in text, "missing '$s' in:\n$text")
        val images = onAllNodes(
            SemanticsMatcher("image") { it.config.getOrNull(SemanticsProperties.Role) == Role.Image },
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue(images.isEmpty(), "unexpected image nodes: ${images.size}")
    }

    private fun ComposeUiTest.save(name: String) {
        val image = onAllNodes(isRoot()).onFirst().captureToImage()
        val file = File(outDir, "$name.png")
        ImageIO.write(image.toAwtImage(), "png", file)
        assertTrue(file.length() > 0, "$name.png is empty")
    }

    private fun bubble(name: String, dark: Boolean, sent: Boolean) = runDesktopComposeUiTest(
        width = (WIDTH * SCALE).toInt(),
        height = (HEIGHT * SCALE).toInt(),
    ) {
        setContent {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false, updatesSystemChrome = false) {
                val container = if (sent) HomebaseTheme.extendedColors.bubbleSentSurface else MaterialTheme.colorScheme.surfaceContainerHigh
                val content = if (sent) HomebaseTheme.extendedColors.bubbleSentOnSurface else MaterialTheme.colorScheme.onSurface
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                    Box(Modifier.widthIn(max = 360.dp).background(container, MaterialTheme.shapes.large).padding(12.dp).testTag("bubble")) {
                        ChatMarkdown(
                            content = source,
                            color = content,
                            style = MaterialTheme.typography.bodyLarge,
                            mentions = MentionContext(sentBubble = sent),
                        )
                    }
                }
            }
        }
        assertShowcase()
        waitForIdle()
        save(name)
    }

    private fun composer(name: String, dark: Boolean) = runDesktopComposeUiTest(
        width = (WIDTH * SCALE).toInt(),
        height = (HEIGHT * SCALE).toInt(),
    ) {
        setContent {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false, updatesSystemChrome = false) {
                val state = rememberRichTextState()
                remember(state) { state.applyMarkDownContent(source) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp)) {
                        RichTextEditor(state = state, modifier = Modifier.widthIn(max = 360.dp))
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { "Heading one" in allText() }
        waitForIdle()
        save(name)
    }

    @Test
    fun ownBubbleLight() = bubble("own-light", dark = false, sent = true)

    @Test
    fun ownBubbleDark() = bubble("own-dark", dark = true, sent = true)

    @Test
    fun otherBubbleLight() = bubble("other-light", dark = false, sent = false)

    @Test
    fun otherBubbleDark() = bubble("other-dark", dark = true, sent = false)

    @Test
    fun composerLight() = composer("composer-light", dark = false)

    @Test
    fun composerDark() = composer("composer-dark", dark = true)

    @Test
    fun legacyPlateConvertsToMarkdown() {
        assertTrue(legacyPlate.contains("**legacy**"), legacyPlate)
    }

    private companion object {
        const val SCALE = 1.5f
        const val WIDTH = 392
        const val HEIGHT = 2400
    }
}
