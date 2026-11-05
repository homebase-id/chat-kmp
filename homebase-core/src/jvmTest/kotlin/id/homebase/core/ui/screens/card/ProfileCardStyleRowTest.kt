package id.homebase.core.ui.screens.card

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ProfileCardStyleRowTest {

    private val outDir: File? = System.getenv("CARD_SHOTS_DIR")?.let(::File)?.also { it.mkdirs() }

    private val labels = mapOf(
        CardDesign.POSTER to listOf("Default", "Green", "Cobalt", "Slate", "Plum"),
        CardDesign.BOARD to listOf("Default", "Green", "Purple", "Graphite", "Light"),
        CardDesign.COLLAGE to listOf("Default", "Mist", "Blue", "Slate", "Night"),
        CardDesign.DOSSIER to listOf("Default", "Graphite", "Blueprint", "Plum", "Paper"),
        CardDesign.LINKS to listOf("Default", "Blue", "Cobalt", "Plum", "Dark"),
    )

    private fun row(design: String, fontScale: Float, dark: Boolean, check: (still: Boolean) -> Unit) =
        runDesktopComposeUiTest(width = 360, height = 200) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    HomebaseTheme(darkTheme = dark, updatesSystemChrome = false) {
                        CardStyleRow(cardStylesFor(design), CardOverrides(), design, enabled = true, onStyle = {})
                    }
                }
            }
            waitForIdle()
            val still = onAllNodes(hasScrollAction()).fetchSemanticsNodes().isEmpty()
            for (label in labels.getValue(design)) {
                val node = onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
                assertTrue(node.size.width > 0, "$design x$fontScale: $label has no width")
                val tile = generateSequence(node.parent) { it.parent }.first { it.config.contains(SemanticsProperties.Role) || it.config.contains(SemanticsProperties.Selected) }
                assertTrue(node.size.width <= tile.size.width, "$design x$fontScale: $label is wider than its tile")
                if (still) assertTrue(node.boundsInRoot.left >= 0f && node.boundsInRoot.right <= 360f, "$design x$fontScale: $label ${node.boundsInRoot} leaves the row")
            }
            outDir?.let { ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(it, "style-row-$design-x$fontScale-${if (dark) "dark" else "light"}.png")) }
            check(still)
        }

    @Test
    fun everyDesignsFiveTilesSitInOneStillRowAt360dp() {
        for (design in CardDesign.all) for (dark in listOf(false, true)) {
            assertEquals(5, cardStylesFor(design).size, design)
            row(design, 1f, dark) { still -> assertTrue(still, "$design scrolls at 360dp") }
        }
    }

    // At 1.6 the names outgrow five 60dp tiles, so the row scrolls instead of cutting a name.
    @Test
    fun atLargeTextNoNameIsCut() {
        for (design in CardDesign.all) for (dark in listOf(false, true)) row(design, 1.6f, dark) {}
    }
}
