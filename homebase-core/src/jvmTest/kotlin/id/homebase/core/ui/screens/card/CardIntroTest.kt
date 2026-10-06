package id.homebase.core.ui.screens.card

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class CardIntroTest {

    private val outDir: File? = System.getenv("CARD_SHOTS_DIR")?.let(::File)?.also { it.mkdirs() }

    private fun card(audience: CardAudience, priority: Int = PUBLIC_CARD_PRIORITY, design: String = CardDesign.BOARD) =
        ProfileCard(Uuid.NIL, Uuid.NIL, audience, design, priority = priority)

    private val family = CardAudience.Circle(FAMILY_CIRCLE_ID, "Family")
    private val friends = CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends")
    private val work = CardAudience.Circle(WORK_CIRCLE_ID, "Work")
    private val fixed = listOf(
        card(CardAudience.Public),
        card(family, 10, CardDesign.POSTER),
        card(friends, 20),
        card(work, 30, CardDesign.DOSSIER),
    )

    private fun stateOf(vararg extra: CardAudience, base: List<ProfileCard> = fixed) =
        ProfileCardUiState(cards = base + extra.mapIndexed { i, a -> card(a, 40 + i) })

    private fun stills(state: ProfileCardUiState): Map<String, ImageBitmap> =
        state.cards.associate { audienceKey(it.audience) to ImageBitmap(2, 2) }

    @androidx.compose.runtime.Composable
    private fun intro(
        state: ProfileCardUiState,
        tiles: Map<String, ImageBitmap> = emptyMap(),
        onOpen: (CardAudience) -> Unit = {},
        onTilePainted: (CardAudience) -> Unit = {},
    ) = CardIntro(
        uiState = state,
        tiles = tiles,
        host = null,
        revision = 0,
        onOpen = onOpen,
        onClose = {},
        onRetry = {},
        onCapture = {},
        onTilePainted = onTilePainted,
    )

    private fun render(name: String, state: ProfileCardUiState, fontScale: Float = 1f, w: Int = 412, h: Int = 892, act: (Int) -> Unit = {}): List<CardAudience> {
        val opened = mutableListOf<CardAudience>()
        runDesktopComposeUiTest(width = w * 2, height = h * 2) {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                    HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                        intro(state, onOpen = { opened += it })
                    }
                }
            }
            mainClock.advanceTimeBy(1_500)
            act(0)
            outDir?.let { ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(it, "k7-$name.png")) }
        }
        return opened
    }

    @Test
    fun everyFixedCardShowsItsCircleTitleAndSelectingOneOpensIt() {
        val opened = mutableListOf<CardAudience>()
        runDesktopComposeUiTest(width = 824, height = 1784) {
            setContent {
                HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                    intro(stateOf(), onOpen = { opened += it })
                }
            }
            listOf("Public", "Family", "Friends", "Work").forEach { onNodeWithText(it).assertExists() }
            onNodeWithContentDescription("Open the Friends card").performClick()
            onNodeWithContentDescription("Open the Public card").performClick()
        }
        assertEquals(listOf(friends, CardAudience.Public), opened)
    }

    @Test
    fun theViewerAudienceChipIsATitleOnlyWithNoSwitcher() {
        var closed = 0
        runDesktopComposeUiTest(width = 824, height = 1784) {
            setContent {
                HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                    SheetTopChrome(
                        uiState = stateOf().copy(selectedAudience = friends, viewing = true),
                        onClose = { closed++ },
                        bandDrag = Modifier,
                        handleDrag = Modifier,
                    )
                }
            }
            val chip = onNode(hasContentDescription("Card for Friends", substring = true))
            chip.assertExists()
            chip.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            chip.performClick()
            waitForIdle()
            onAllNodes(isPopup()).assertCountEquals(0)
            assertEquals(0, closed)
        }
    }

    @Test
    fun eachTileReportsItsOwnPaintOnceItsStillIsShown() {
        val painted = mutableListOf<CardAudience>()
        val state = stateOf()
        runDesktopComposeUiTest(width = 824, height = 1784) {
            setContent {
                HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                    intro(state, tiles = stills(state), onTilePainted = { painted += it })
                }
            }
            waitForIdle()
        }
        assertEquals(state.cards.map { it.audience }.toSet(), painted.toSet())
        assertEquals(state.cards.size, painted.size)
    }

    @Test
    fun tilesWithoutAStillReportNothingYet() {
        val painted = mutableListOf<CardAudience>()
        runDesktopComposeUiTest(width = 824, height = 1784) {
            setContent {
                HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                    intro(stateOf(), onTilePainted = { painted += it })
                }
            }
            waitForIdle()
        }
        assertEquals(emptyList(), painted)
    }

    @Test
    fun largeFontsFallBackToOneColumnSoNamesDoNotBreakMidWord() {
        val long = CardAudience.Circle("long", "Climbing partners from the Tuesday bouldering gym night")
        runDesktopComposeUiTest(width = 824, height = 1784) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
                    HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                        intro(stateOf(long))
                    }
                }
            }
            val publicLeft = onNodeWithContentDescription("Open the Public card").fetchSemanticsNode().boundsInRoot.left
            val familyLeft = onNodeWithContentDescription("Open the Family card").fetchSemanticsNode().boundsInRoot.left
            assertEquals(publicLeft, familyLeft)
        }
    }

    @Test
    fun layoutsHoldUpAcrossCircleCountsLongNamesAndLargeFonts() {
        val long = CardAudience.Circle("long", "Climbing partners from the Tuesday bouldering gym night")
        fun circle(n: Int) = CardAudience.Circle("extra$n", "Circle $n")
        render("intro-1-circle-2-cards", stateOf(base = listOf(card(CardAudience.Public), card(friends, 20))))
        render("intro-2-circles-3-cards", stateOf(base = listOf(card(CardAudience.Public), card(family, 10, CardDesign.POSTER), card(friends, 20))))
        render("intro-3-circles-4-cards", stateOf())
        render("intro-4-circles-5-cards", stateOf(circle(1)))
        render("intro-5-circles-6-cards", stateOf(circle(1), circle(2)))
        render("intro-long-name", stateOf(long))
        render("intro-font-2x", stateOf(long), fontScale = 2f)
        render("intro-small-phone", stateOf(circle(1)), w = 360, h = 640)
        render("intro-wide", stateOf(circle(1)), w = 1000, h = 800)
    }
}
