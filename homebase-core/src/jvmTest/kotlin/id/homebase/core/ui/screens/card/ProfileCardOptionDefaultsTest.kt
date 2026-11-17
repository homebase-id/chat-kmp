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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun panel(design: String, onOption: (CardOption, String?) -> Unit = { _, _ -> }, check: ComposeUiTest.() -> Unit) =
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
                                onOption = onOption,
                                onBlockOrder = {},
                                onSave = {},
                                snackbarHostState = remember { SnackbarHostState() },
                            ) { Box(Modifier.fillMaxSize()) }
                        }
                    }
                }
            }
            waitForIdle()
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
        CardDesign.BOARD to setOf("Ocean", "Medium", "Circle", "Boxed", "Bar", "Alternates", "Montserrat"),
        CardDesign.DOSSIER to setOf("Onyx", "Medium", "Square", "Rows", "Handles", "Archivo Black", "Space Mono"),
        CardDesign.COLLAGE to setOf("Linen", "Medium", "Square", "Wordmarks", "Caveat", "Montserrat"),
        CardDesign.POSTER to setOf("Espresso", "Plain", "Icons", "Newsreader"),
        CardDesign.LINKS to setOf("Frost", "Medium", "Circle", "Icons", "Montserrat"),
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
    fun noTabOffersADefaultChipOrSwatchAndBackgroundSelectsThePresetPattern() {
        val presetBackground = mapOf(
            CardDesign.BOARD to "Circles", CardDesign.COLLAGE to "Speckle",
            CardDesign.POSTER to "No pattern", CardDesign.DOSSIER to "No pattern", CardDesign.LINKS to "No pattern",
        )
        val presetAccent = mapOf(
            CardDesign.BOARD to "#4FD1C5", CardDesign.COLLAGE to "#B4553A", CardDesign.POSTER to "#F4F0EA",
            CardDesign.DOSSIER to "#7FD4E8", CardDesign.LINKS to "#1A66D6",
        )
        for ((design, label) in presetBackground) panel(design) {
            val tabs = tabNames()
            assertTrue(tabs.isNotEmpty())
            for (tab in tabs) {
                pickTab(tab)
                onAllNodesWithText("Default").assertCountEquals(0)
                onAllNodesWithContentDescription("Default").assertCountEquals(0)
                if (tab == "Background pattern") {
                    val selected = onAllNodes(isSelectedRadio).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.ContentDescription)!!.joinToString() }
                    assertEquals(listOf(label), selected, "$design selects its preset background")
                }
                if (tab == "Accent colour") {
                    val selected = onAllNodes(isSelectedRadio).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.ContentDescription)!!.joinToString() }
                    assertEquals(1, selected.size, "$design accent selection")
                    assertTrue(selected.single().contains(presetAccent.getValue(design)), "$design selects its preset accent: $selected")
                }
            }
        }
    }

    private val isSelectedRadio = SemanticsMatcher("is a selected radio") {
        it.config.getOrNull(SemanticsProperties.Selected) == true && it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
            it.config.getOrNull(SemanticsProperties.ContentDescription) != null
    }

    @Test
    fun everyPortraitShapeDrawsAGlyphThatDiffersFromTheOthers() {
        panel(CardDesign.BOARD) {
            pickTab("Photo size")
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

    private val isRadio = SemanticsMatcher("is a radio") { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }

    @Test
    fun designTabsRunPalettePortraitBackgroundStyleAndNoneIsCalledPresets() {
        panel(CardDesign.BOARD) {
            val tabs = tabNames()
            assertEquals(listOf("Ready-made styles", "Photo size", "Background pattern", "Content style"), tabs.take(4))
            assertTrue(tabs.none { it.contains("Presets", ignoreCase = true) })
        }
    }

    @Test
    fun backgroundOffersOnlyWhatTheWebOverrideListAcceptsBesideThePreset() {
        val web = setOf("none", "circles", "blobs", "dots", "waves", "lines", "rings", "grid", "checker", "zigzag", "confetti", "contours", "halftone", "sparkles")
        for (design in listOf(CardDesign.BOARD, CardDesign.COLLAGE, CardDesign.POSTER, CardDesign.DOSSIER, CardDesign.LINKS)) {
            val extra = CardBackgrounds.variantsFor(design).toSet() - CardBackgrounds.presetFor(design)
            assertTrue(web.containsAll(extra), "$design offers non-web backgrounds: ${extra - web}")
        }
        assertEquals(null, CardBackgrounds.known("speckle"))
    }

    @Test
    fun backgroundTilesSendNullForThePresetAndTheIdOtherwise() {
        for ((design, presetLabel, other, otherId) in listOf(
            listOf(CardDesign.BOARD, "Circles", "Dots", "dots"),
            listOf(CardDesign.COLLAGE, "Speckle", "Blobs", "blobs"),
        )) {
            val calls = mutableListOf<Pair<CardOption, String?>>()
            panel(design, onOption = { o, v -> calls += o to v }) {
                pickTab("Background pattern")
                onNodeWithContentDescription(presetLabel).performClick()
                onNodeWithContentDescription(other).performClick()
                assertEquals(listOf(CardOption.BACKGROUND to null, CardOption.BACKGROUND to otherId), calls, design)
            }
        }
    }

    @Test
    fun accentSwatchesSendNullForThePresetAndTheHexOtherwise() {
        for ((design, preset) in listOf(CardDesign.BOARD to "#4FD1C5", CardDesign.COLLAGE to "#B4553A")) {
            val calls = mutableListOf<Pair<CardOption, String?>>()
            panel(design, onOption = { o, v -> calls += o to v }) {
                pickTab("Accent colour")
                val descriptions = onAllNodes(isRadio).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.ContentDescription)!!.joinToString() }
                val presetDesc = descriptions.single { it.contains(preset, ignoreCase = true) }
                val otherDesc = descriptions.first { it != presetDesc }
                onNodeWithContentDescription(presetDesc).performClick()
                onNodeWithContentDescription(otherDesc).performClick()
                val hex = Regex("#[0-9A-Fa-f]{6}").find(otherDesc)!!.value
                assertEquals(listOf(CardOption.ACCENT to null, CardOption.ACCENT to hex), calls, design)
            }
        }
    }

    private val isToggle = SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)

    @Test
    fun thePresetsValueIsTheFirstChipInEveryRowAndIsSelectedWithNoOverrides() {
        for (design in presetChips.keys) panel(design) {
            var rows = 0
            for (tab in tabNames()) {
                pickTab(tab)
                val chips = onAllNodes(isToggle).fetchSemanticsNodes()
                if (chips.isEmpty()) continue
                rows++
                val states = chips.map { it.config.getOrNull(SemanticsProperties.ToggleableState) }
                if (tab == "Photo size") {
                    assertEquals(listOf(ToggleableState.Off, ToggleableState.On, ToggleableState.Off), states.take(3), "$design/$tab: medium is not the one selected size")
                    assertEquals(ToggleableState.On, states[3], "$design/$tab: the shape row's first chip is not selected")
                    continue
                }
                assertEquals(ToggleableState.On, states.first(), "$design/$tab: first chip is not the selected one")
                assertEquals(1, states.count { it == ToggleableState.On }, "$design/$tab: exactly one chip is selected")
            }
            assertTrue(rows >= 3, "$design checked only $rows segmented rows")
        }
    }

    @Test
    fun pickingThePresetValueSendsNullAndAnotherSendsItsId() {
        val calls = mutableListOf<Pair<CardOption, String?>>()
        panel(CardDesign.COLLAGE, onOption = { o, v -> calls += o to v }) {
            pickTab("Photo size")
            onNodeWithContentDescription("Circle").performClick()
            onNodeWithContentDescription("Square").performClick()
            assertEquals(listOf(CardOption.PORTRAIT_SHAPE to "circle", CardOption.PORTRAIT_SHAPE to null), calls)
        }
    }

    @Test
    fun photoSizeIsThreeChoicesAndMediumSendsNull() {
        val calls = mutableListOf<Pair<CardOption, String?>>()
        panel(CardDesign.LINKS, onOption = { o, v -> calls += o to v }) {
            pickTab("Photo size")
            onNodeWithText("Small").performClick()
            onNodeWithText("Large").performClick()
            onNodeWithText("Medium").performClick()
            assertEquals(listOf(CardOption.PORTRAIT_SIZE to "small", CardOption.PORTRAIT_SIZE to "large", CardOption.PORTRAIT_SIZE to null), calls)
        }
    }

    @Test
    fun aDesignWithoutAPortraitOffersNoPhotoSize() {
        panel(CardDesign.POSTER) { assertTrue("Photo size" !in tabNames()) }
    }
}
