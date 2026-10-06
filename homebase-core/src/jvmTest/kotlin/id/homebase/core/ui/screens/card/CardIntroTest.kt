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
import androidx.compose.ui.test.performClick
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

    private fun render(name: String, state: ProfileCardUiState, fontScale: Float = 1f, w: Int = 412, h: Int = 892, act: (Int) -> Unit = {}): List<CardAudience> {
        val opened = mutableListOf<CardAudience>()
        runDesktopComposeUiTest(width = w * 2, height = h * 2) {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                    HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                        CardIntro(uiState = state, onOpen = { opened += it }, onClose = {}, onRetry = {}, onPainted = {})
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
                    CardIntro(uiState = stateOf(), onOpen = { opened += it }, onClose = {}, onRetry = {}, onPainted = {})
                }
            }
            listOf("Public", "Family", "Friends", "Work").forEach { onNodeWithText(it).assertExists() }
            onNodeWithContentDescription("Open the Friends card").performClick()
            onNodeWithContentDescription("Open the Public card").performClick()
        }
        assertEquals(listOf(friends, CardAudience.Public), opened)
    }

    @Test
    fun noSwitcherRemainsOnTheIntro() {
        runDesktopComposeUiTest(width = 824, height = 1784) {
            setContent {
                HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                    CardIntro(uiState = stateOf(), onOpen = {}, onClose = {}, onRetry = {}, onPainted = {})
                }
            }
            assertFalse(onAllNodesWithContentDescription("Choose another card", substring = true).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun paintReportsEveryTitleOnce() {
        val painted = mutableListOf<List<String>>()
        runDesktopComposeUiTest(width = 824, height = 1784) {
            setContent {
                HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                    CardIntro(uiState = stateOf(), onOpen = {}, onClose = {}, onRetry = {}, onPainted = { painted += it })
                }
            }
            waitForIdle()
        }
        assertEquals(listOf(listOf("Public", "Family", "Friends", "Work")), painted)
    }

    @Test
    fun layoutsHoldUpAcrossCircleCountsLongNamesAndLargeFonts() {
        val long = CardAudience.Circle("long", "Climbing partners from the Tuesday bouldering gym night")
        val two = stateOf(base = listOf(card(CardAudience.Public), card(friends, 20)))
        render("intro-2-cards", two)
        render("intro-4-cards", stateOf())
        render("intro-5-cards", stateOf(CardAudience.Circle("x", "Acquaintances")))
        render("intro-long-name", stateOf(long))
        render("intro-font-2x", stateOf(long), fontScale = 2f)
        render("intro-small-phone", stateOf(CardAudience.Circle("x", "Acquaintances")), w = 360, h = 640)
        render("intro-wide", stateOf(CardAudience.Circle("x", "Acquaintances")), w = 1000, h = 800)
    }
}
