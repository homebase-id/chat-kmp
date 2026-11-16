package id.homebase.core.ui.screens.card

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.Density
import id.homebase.core.ui.theme.HomebaseTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class ProfileCardOptionDefaultsTest {

    private val outDir: File? = System.getenv("CARD_SHOTS_DIR")?.let(::File)?.also { it.mkdirs() }

    private fun state(design: String) = ProfileCardUiState(
        isCardReady = true,
        cards = listOf(ProfileCard(Uuid.random(), Uuid.random(), CardAudience.Public, design)),
        previewDesign = design,
    )

    private fun panel(design: String, check: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 780, height = 1688) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                    HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                        CardExpressiveTheme {
                            ProfileCardEditorContent(
                                uiState = state(design),
                                mode = EditorMode.Design,
                                step = EditorStep.Customise,
                                onStep = {},
                                onBack = {},
                                onSelect = {},
                                onOption = { _, _ -> },
                                onBlockOrder = {},
                                onSave = {},
                                snackbarHostState = remember { SnackbarHostState() },
                            ) { Box(Modifier.fillMaxSize()) }
                        }
                    }
                }
            }
            waitForIdle()
            if (System.getenv("K16_DUMP") != null) onRoot().printToLog("K16")
            check()
        }

    private val isOn = SemanticsMatcher("is on") { it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On }
    private val isSelectedPreset = SemanticsMatcher("is the selected preset") {
        it.config.getOrNull(SemanticsProperties.Selected) == true && it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
            it.config.getOrNull(SemanticsProperties.Text) != null
    }
    private val isTab = SemanticsMatcher("is a tab") {
        it.config.contains(SemanticsProperties.Selected) && it.config.contains(SemanticsProperties.ContentDescription) && it.config.getOrNull(SemanticsProperties.Role) == null
    }

    // The tool names, read once on the first tab; later tabs show swatches that look alike.
    private fun ComposeUiTest.tabNames(): List<String> =
        onAllNodes(isTab).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.ContentDescription)!!.joinToString() }

    private fun ComposeUiTest.pickTab(name: String) {
        onNodeWithContentDescription(name).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.selectedChips(): List<String> =
        onAllNodes(isOn).fetchSemanticsNodes().map { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            text?.takeIf { it.isNotEmpty() } ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("").orEmpty()
        } + onAllNodes(isSelectedPreset).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { t -> t.text } }

    // The labels each open design's preset draws with, from odin-js cards/presets.ts.
    private val presetChips = mapOf(
        CardDesign.BOARD to setOf("Ocean", "Circle", "Boxed", "Bar", "Alternates", "Montserrat"),
        CardDesign.DOSSIER to setOf("Onyx", "Square", "Rows", "Handles", "Archivo Black", "Space Mono"),
        CardDesign.COLLAGE to setOf("Linen", "Square", "Wordmarks", "Caveat", "Montserrat"),
        CardDesign.POSTER to setOf("Espresso", "Plain", "Icons", "Newsreader"),
        CardDesign.LINKS to setOf("Frost", "Circle", "Icons", "Montserrat"),
    )

    @Test
    fun withNoOverridesEveryOptionRowSelectsWhatTheDesignsPresetDraws() {
        for ((design, expected) in presetChips) panel(design) {
            val seen = mutableSetOf<String>()
            for (tab in tabNames()) {
                pickTab(tab)
                onAllNodesWithText("Default").assertCountEquals(0)
                seen += selectedChips()
            }
            assertEquals(expected, seen, "$design selects its own preset values")
        }
    }

    @Test
    fun noRowOffersADefaultChipExceptTheNoneChoiceOfBackgroundAndAccent() {
        panel(CardDesign.BOARD) {
            for (tab in tabNames()) {
                pickTab(tab)
                onAllNodesWithText("Default").assertCountEquals(0)
                val none = onAllNodesWithContentDescription("Default").fetchSemanticsNodes().size
                val noneRow = tab == "Background pattern" || tab == "Accent colour"
                assertEquals(if (noneRow) 1 else 0, none, "tab '$tab' Default chips")
            }
        }
    }

    @Test
    fun everyPortraitShapeDrawsAGlyphThatDiffersFromTheOthers() {
        panel(CardDesign.BOARD) {
            pickTab("Portrait shape")
            val shapes = listOf("Circle", "Square", "Rounded", "Ellipse")
            val pixels = shapes.associateWith { name ->
                val image = onNodeWithContentDescription(name).captureToImage().toAwtImage()
                outDir?.let { ImageIO.write(image, "png", File(it, "k16-portrait-shape-${name.lowercase()}.png")) }
                image.pixelHash()
            }
            outDir?.let { ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(it, "k16-portrait-row.png")) }
            for (a in shapes) for (b in shapes) if (a < b) assertNotEquals(pixels.getValue(a), pixels.getValue(b), "$a and $b render the same glyph")
            assertTrue(pixels.values.toSet().size == shapes.size)
        }
    }

    // The glyph's silhouette: the pixels of the centre colour inside a 40px square at the chip's middle.
    private fun BufferedImage.pixelHash(): List<Int> {
        val cx = width / 2
        val cy = height / 2
        val ink = getRGB(cx, cy)
        return (cy - 20 until cy + 20).flatMap { y -> (cx - 20 until cx + 20).map { x -> if (getRGB(x, y) == ink) 1 else 0 } }
    }
}
