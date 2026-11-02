package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.Base64Bytes
import id.homebase.core.ui.theme.HomebaseTheme
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class FilesyncThumbnailScreenshotTest {
    private val outDir = File("../build/polish-shots").apply { mkdirs() }
    private val previewTag = hasTestTag(FILE_TILE_PREVIEW_TAG)

    private fun thumb(width: Int, height: Int, draw: (java.awt.Graphics2D) -> Unit): EmbeddedThumb {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().also(draw).dispose()
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return EmbeddedThumb(width, height, "image/png", Base64Bytes(png))
    }

    private val photo = thumb(20, 13) { g ->
        g.paint = GradientPaint(0f, 0f, Color(0xF2994A), 20f, 13f, Color(0x2D6CDF))
        g.fillRect(0, 0, 20, 13)
        g.color = Color(0x1B4332)
        g.fillOval(3, 7, 14, 12)
    }

    private val page = thumb(14, 20) { g ->
        g.color = Color.WHITE
        g.fillRect(0, 0, 14, 20)
        g.color = Color(0x444444)
        for (y in 3..17 step 3) g.fillRect(2, y, if (y % 2 == 0) 10 else 8, 1)
    }

    private fun file(name: String, preview: EmbeddedThumb? = null, removed: Boolean = false) =
        FilesyncNode(Uuid.random(), Uuid.random(), isFolder = false, name = name, modifiedMs = 1, sizeBytes = 5, removed = removed, preview = preview)

    private val rows = listOf(
        FilesyncNode(Uuid.random(), Uuid.random(), isFolder = true, name = "Trip", modifiedMs = null, sizeBytes = null),
        file("sunset.jpg", photo),
        file("contract.pdf", page),
        file("notes.txt"),
        file("old-scan.pdf"),
        file("deleted.png", photo, removed = true),
    )
    private val withPreview = rows.count { it.preview != null }

    private fun ComposeUiTest.render(name: String, dark: Boolean, content: @Composable () -> Unit) {
        setContent {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                Surface(color = MaterialTheme.colorScheme.surface) { content() }
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(previewTag, useUnmergedTree = true).fetchSemanticsNodes().size == withPreview }
        waitForIdle()
        assertEquals(withPreview, onAllNodes(previewTag, useUnmergedTree = true).fetchSemanticsNodes().size, name)
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        val colors = HashSet<Int>()
        for (x in 0 until image.width step 5) for (y in 0 until image.height step 5) colors += image.getRGB(x, y)
        assertTrue(colors.size > 8, "$name rendered blank")
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }

    @Test
    fun phoneListShowsPreviewsAndKeepsTypeIcons() {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(411, 520) {
                render("phone-${if (dark) "dark" else "light"}", dark) {
                    Column {
                        rows.forEachIndexed { i, node -> FilesyncNodeRow(node, "5 B", selected = i == 2) }
                    }
                }
            }
        }
    }

    @Test
    fun wideTableShowsPreviewsAndKeepsTypeIcons() {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(1100, 300) {
                render("wide-${if (dark) "dark" else "light"}", dark) {
                    Column {
                        rows.forEachIndexed { i, node ->
                            TableRow(
                                node = node,
                                selected = i == 2,
                                progress = null,
                                columns = TableColumns(modified = true, kind = true),
                                actions = FilesyncBrowserActions(onAction = {}, onAskRestore = {}),
                                onSelect = {},
                            )
                        }
                    }
                }
            }
        }
    }
}
