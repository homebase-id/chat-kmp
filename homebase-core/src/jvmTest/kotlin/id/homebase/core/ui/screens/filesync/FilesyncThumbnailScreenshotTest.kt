package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
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

    private val modified = 1_760_000_000_000L

    private fun file(name: String, bytes: Long, days: Int, preview: EmbeddedThumb? = null, removed: Boolean = false): FilesyncNode {
        val ms = modified - days * 86_400_000L
        return FilesyncNode(Uuid.random(), Uuid.random(), isFolder = false, name = name, modifiedMs = ms, sizeBytes = bytes, removed = removed, updatedMs = ms, preview = preview)
    }

    private val rows = listOf(
        FilesyncNode(Uuid.random(), Uuid.random(), isFolder = true, name = "Trip to Lisbon", modifiedMs = null, sizeBytes = null),
        file("sunset-over-the-tagus-from-miradouro-da-senhora-do-monte-final-edit.jpg", 4_812_000, 3, photo),
        file("Rental contract 2026 signed.pdf", 1_270_000, 12, page),
        file("packing-notes.txt", 2_140, 40),
        file("old-scan.pdf", 88_400_000, 200),
        file("deleted-photo.png", 612_000, 75, photo, removed = true),
    )
    private fun ComposeUiTest.render(name: String, dark: Boolean, previews: Int = 3, content: @Composable () -> Unit) {
        setContent {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                Surface(color = MaterialTheme.colorScheme.surface) { content() }
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(previewTag, useUnmergedTree = true).fetchSemanticsNodes().size == previews }
        waitForIdle()
        assertEquals(previews, onAllNodes(previewTag, useUnmergedTree = true).fetchSemanticsNodes().size, name)
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        val colors = HashSet<Int>()
        for (x in 0 until image.width step 5) for (y in 0 until image.height step 5) colors += image.getRGB(x, y)
        assertTrue(colors.size > 8, "$name rendered blank")
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }

    @Test
    fun phoneListShowsPreviewsAndKeepsTypeIcons() {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(411, 540) {
                render("phone-${if (dark) "dark" else "light"}", dark) {
                    Column {
                        rows.forEachIndexed { i, node -> FilesyncNodeRow(node, nodeDetails(node), selected = i == 2) }
                    }
                }
            }
        }
    }

    @Test
    fun wideTableShowsPreviewsAndKeepsTypeIcons() {
        for (dark in listOf(false, true)) {
            wide("wide", 1100, TableColumns(modified = true, kind = true), dark)
            // The browser's real share of a 1100-wide window, beside the sidebar and the info pane.
            wide("wide-pane", 562, TableColumns(modified = true, kind = false), dark)
        }
    }

    private fun wide(name: String, width: Int, columns: TableColumns, dark: Boolean) {
        runDesktopComposeUiTest(width, 380) {
            render("$name-${if (dark) "dark" else "light"}", dark) {
                Column {
                    TableHeader(FilesyncSort(), selectedCount = 1, visibleCount = rows.size, columns = columns, onAction = {})
                    Column(Modifier.padding(horizontal = FilesyncRowInset, vertical = 4.dp)) {
                        rows.forEachIndexed { i, node ->
                            TableRow(
                                node = node,
                                selected = i == 2,
                                progress = null,
                                columns = columns,
                                actions = FilesyncBrowserActions(onAction = {}, onAskRestore = {}),
                                onSelect = {},
                            )
                        }
                    }
                }
            }
        }
    }

    // There is no grid in Filesync; the info pane's tile is the largest a preview is drawn.
    @Test
    fun infoPaneTileKeepsTheFit() {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(411, 120) {
                render("tiles-${if (dark) "dark" else "light"}", dark) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        rows.forEach { FileTypeTile(it, size = 56.dp, deleted = it.removed) }
                    }
                }
            }
        }
    }
}
