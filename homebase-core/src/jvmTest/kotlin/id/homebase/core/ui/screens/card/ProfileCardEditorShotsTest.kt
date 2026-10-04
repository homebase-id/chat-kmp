package id.homebase.core.ui.screens.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Renders the card editor's chrome in every state, light and dark, to PNGs for design review.
 * The live card is a WebView that can't render here, so a flat stand-in in the design's base colour takes its place.
 * Set CARD_SHOTS_DIR to write the images; without it the states are only composed.
 */
@OptIn(ExperimentalTestApi::class)
class ProfileCardEditorShotsTest {

    private val outDir: File? = System.getenv("CARD_SHOTS_DIR")?.let(::File)?.also { it.mkdirs() }

    private data class Shot(
        val name: String,
        val state: ProfileCardUiState,
        val step: EditorStep = EditorStep.Design,
        val preview: Preview = Preview.Ready,
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
        val widthDp: Int = PHONE_W,
        val heightDp: Int = PHONE_H,
        val act: ComposeUiTest.() -> Unit = {},
    )

    private enum class Preview { Ready, Loading, LoadFailed, CardFailed }

    private fun card(audience: CardAudience, design: String = CardDesign.BOARD) =
        ProfileCard(id = Uuid.random(), versionTag = Uuid.random(), audience = audience, design = design)

    private val public = card(CardAudience.Public)
    private val friends = CardAudience.Circle("c1", "Friends")
    private val longCircle = CardAudience.Circle("c2", "Climbing partners from the Tuesday bouldering gym night")
    private val base = ProfileCardUiState(isCardReady = true, cards = listOf(public))
    private val edited = base.copy(
        previewOverrides = CardOverrides(palette = CardPalette(accent = "#F26B5B"), socials = "bar", type = CardTypeface(display = "newsreader")),
    )

    private fun tool(description: String): ComposeUiTest.() -> Unit = {
        // A mouse click, then the pointer leaves, so no hover highlight is left on whatever ends up under it.
        onNodeWithContentDescription(description).performMouseInput {
            click()
            moveTo(Offset(-PARK_PX, -PARK_PX))
        }
        mainClock.advanceTimeBy(SETTLE_MS)
    }

    private val shots = listOf(
        Shot("01-design-public", base),
        Shot("02-design-unsaved", base.copy(previewDesign = CardDesign.POSTER)),
        Shot("03-design-saving", base.copy(previewDesign = CardDesign.COLLAGE, isSavingDesign = true)),
        Shot("03b-customise-saving", edited.copy(isSavingDesign = true), EditorStep.Customise),
        Shot("04-customise-accent", edited, EditorStep.Customise),
        Shot("05-customise-heading-font", edited, EditorStep.Customise, act = tool("Heading font")),
        Shot("06-customise-portrait", edited, EditorStep.Customise, act = tool("Portrait shape")),
        Shot("07-customise-socials", edited, EditorStep.Customise, act = tool("Social links style")),
        Shot("08-customise-order", edited, EditorStep.Customise, act = tool("Section order")),
        Shot("09-customise-poster", base.copy(previewDesign = CardDesign.POSTER), EditorStep.Customise),
        Shot("10-customise-empty", base.copy(previewDesign = "zine"), EditorStep.Customise),
        Shot("11-circle-card", base.copy(cards = listOf(public, card(friends)), selectedAudience = friends)),
        Shot("12-circle-long-name", base.copy(cards = listOf(public, card(longCircle)), selectedAudience = longCircle), EditorStep.Customise),
        Shot("13-preview-loading", base.copy(isCardReady = false), preview = Preview.Loading),
        Shot("14-preview-load-failed", base.copy(loadFailed = true), preview = Preview.LoadFailed),
        Shot("15-preview-card-failed", base.copy(cardFailed = true), preview = Preview.CardFailed),
        Shot("16-font-scale-design", base.copy(previewDesign = CardDesign.POSTER), fontScale = 1.6f),
        Shot("17-font-scale-customise", edited, EditorStep.Customise, fontScale = 1.6f),
        Shot("17b-font-scale-fonts", edited, EditorStep.Customise, fontScale = 1.6f, act = tool("Heading font")),
        Shot("17c-font-scale-order", edited, EditorStep.Customise, fontScale = 1.6f, act = tool("Section order")),
        Shot("18-rtl-customise", edited, EditorStep.Customise, rtl = true, act = tool("Portrait shape")),
        Shot("18b-rtl-design", base, rtl = true),
        Shot("19-wide-design", base, widthDp = 900, heightDp = 820),
        Shot("19b-wide-customise", edited, EditorStep.Customise, widthDp = 900, heightDp = 820, act = tool("Section order")),
        Shot("20-small-phone-customise", edited, EditorStep.Customise, widthDp = 360, heightDp = 640, act = tool("Section order")),
        Shot("21-small-phone-design", base.copy(previewDesign = CardDesign.DOSSIER), widthDp = 360, heightDp = 640),
        Shot("22-small-phone-fonts", edited, EditorStep.Customise, widthDp = 360, heightDp = 640, act = tool("Body font")),
        Shot("23-access-note", base.copy(designAccessMissing = true), EditorStep.Design),
        Shot("24-access-note-customise", edited.copy(designAccessMissing = true), EditorStep.Customise, act = tool("Social links style")),
    )

    private fun circles(n: Int) = List(n) { CardCircle("c$it", CIRCLE_NAMES[it % CIRCLE_NAMES.size], memberCount = it * 3 % 11) }

    private class ViewerShot(val name: String, val state: ProfileCardUiState, val act: ComposeUiTest.() -> Unit = {}, val popup: Boolean = false)

    private val work = CardAudience.Circle("c1", "Work")
    private val withCircle = base.copy(cards = listOf(public, card(work, CardDesign.COLLAGE)), selectedAudience = work)

    private val viewerShots = listOf(
        ViewerShot("v01-public", base.copy(cards = listOf(public, card(work)))),
        ViewerShot("v02-circle", withCircle),
        ViewerShot("v03-home-page-behind", base.copy(isHomePageBehind = true)),
        ViewerShot("v04-exporting", withCircle.copy(isExporting = true)),
        ViewerShot("v05-menu", withCircle, act = { onNodeWithContentDescription("Card for Work. Choose another card.").performClick() }, popup = true),
    )

    @Test
    fun viewerChromeRendersEveryState() {
        for (dark in listOf(false, true)) {
            for (shot in viewerShots) renderViewer(shot, dark)
            renderPopup("v06-delete-dialog", dark) { DeleteCardDialog(label = "Work", onDelete = {}, onDismiss = {}) }
            renderPopup("v07-access-dialog", dark) { DesignAccessDialog(onContinue = {}, onDismiss = {}) }
            renderSheet("v08-add-circle", dark, CirclePicker(circles(12), withCard = setOf("c1", "c4"), loading = false))
            renderSheet("v09-add-circle-few", dark, CirclePicker(circles(3), withCard = setOf("c0"), loading = false))
            renderSheet("v10-add-circle-loading", dark, CirclePicker())
            renderSheet("v11-add-circle-all-taken", dark, CirclePicker(circles(2), withCard = setOf("c0", "c1"), loading = false))
            renderSheet("v12-add-circle-pick", dark, CirclePicker(circles(4), withCard = setOf("c1"), loading = false)) {
                onNodeWithText(CIRCLE_NAMES[2]).performClick()
            }
        }
    }

    @Composable
    private fun themed(dark: Boolean, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(SCALE, 1f)) {
            HomebaseTheme(darkTheme = dark, updatesSystemChrome = false) {
                CardExpressiveTheme(content)
            }
        }
    }

    private fun renderViewer(shot: ViewerShot, dark: Boolean) = runDesktopComposeUiTest(
        width = (PHONE_W * SCALE).toInt(),
        height = (PHONE_H * SCALE).toInt(),
    ) {
        mainClock.autoAdvance = false
        setContent {
            themed(dark) {
                Box(Modifier.fillMaxSize().background(Color(CardDesign.baseArgb(shot.state.design)))) {
                    SheetTopChrome(
                        uiState = shot.state,
                        onSelectCard = {},
                        circleActions = CircleCardActions({}, {}, {}, {}),
                        onClose = {},
                        bandDrag = Modifier,
                        handleDrag = Modifier,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    CardBottomChrome(
                        sharesPublicCard = shot.state.isCircleSelected,
                        isExporting = shot.state.isExporting,
                        canShare = true,
                        saveInsteadOfShare = false,
                        isHomePageBehind = shot.state.isHomePageBehind,
                        onShare = {},
                        onPublish = {},
                        onEdit = {},
                        extraActions = {},
                    )
                }
            }
        }
        mainClock.advanceTimeBy(SETTLE_MS)
        shot.act(this)
        mainClock.advanceTimeBy(SETTLE_MS)
        save(shot.name, dark)
    }

    private fun renderPopup(name: String, dark: Boolean, content: @Composable () -> Unit) = runDesktopComposeUiTest(
        width = (PHONE_W * SCALE).toInt(),
        height = (PHONE_H * SCALE).toInt(),
    ) {
        mainClock.autoAdvance = false
        setContent {
            themed(dark) {
                Box(Modifier.fillMaxSize().background(Color(CardDesign.baseArgb(CardDesign.COLLAGE))))
                content()
            }
        }
        mainClock.advanceTimeBy(SETTLE_MS)
        save(name, dark)
    }

    private fun renderSheet(name: String, dark: Boolean, picker: CirclePicker, act: ComposeUiTest.() -> Unit = {}) = runDesktopComposeUiTest(
        width = (PHONE_W * SCALE).toInt(),
        height = (PHONE_H * SCALE).toInt(),
    ) {
        mainClock.autoAdvance = false
        setContent {
            themed(dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)), contentAlignment = Alignment.BottomCenter) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().heightIn(max = (PHONE_H * 0.9f).dp),
                    ) {
                        Box(Modifier.padding(top = 32.dp)) {
                            CirclePickerContent(picker = picker, onPick = {}, onCancel = {})
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(SETTLE_MS)
        act(this)
        mainClock.advanceTimeBy(SETTLE_MS)
        save(name, dark)
    }

    private fun ComposeUiTest.save(name: String, dark: Boolean) {
        val image = onAllNodes(isRoot()).onFirst().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
        outDir?.let { dir ->
            ImageIO.write(image.toAwtImage(), "png", File(dir, "$name-${if (dark) "dark" else "light"}.png"))
        }
    }

    @Test
    fun editorRendersEveryState() {
        for (dark in listOf(false, true)) {
            for (shot in shots) render(shot, dark)
        }
    }

    private fun render(shot: Shot, dark: Boolean) = runDesktopComposeUiTest(
        width = (shot.widthDp * SCALE).toInt(),
        height = (shot.heightDp * SCALE).toInt(),
    ) {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(SCALE, shot.fontScale),
                LocalLayoutDirection provides if (shot.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                HomebaseTheme(darkTheme = dark, updatesSystemChrome = false) {
                    CardExpressiveTheme {
                        var step by remember { mutableStateOf(shot.step) }
                        ProfileCardEditorContent(
                            uiState = shot.state,
                            step = step,
                            onStep = { step = it },
                            onBack = { step = EditorStep.Design },
                            onSelect = {},
                            onOption = { _, _ -> },
                            onBlockOrder = {},
                            onSave = {},
                            onEditProfile = {},
                            snackbarHostState = remember { SnackbarHostState() },
                        ) {
                            when (shot.preview) {
                                Preview.Ready -> Box(
                                    Modifier.fillMaxSize().background(Color(CardDesign.baseArgb(shot.state.design))),
                                )
                                else -> CardSurface(
                                    uiState = shot.state,
                                    host = null,
                                    backdrop = { Color(CardDesign.baseArgb(shot.state.design)) },
                                    onRetry = {},
                                    paintWhileAttached = {},
                                    modifier = Modifier.fillMaxSize(),
                                    skeleton = true,
                                )
                            }
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(SETTLE_MS)
        shot.act(this)
        mainClock.advanceTimeBy(SETTLE_MS)
        save(shot.name, dark)
    }

    private companion object {
        const val SCALE = 2f
        const val PHONE_W = 412
        const val PHONE_H = 892
        const val SETTLE_MS = 1_500L
        const val PARK_PX = 100_000f
        val CIRCLE_NAMES = listOf("Acquaintances", "Chat", "Emergency Location Access", "Family", "Feed", "Friends", "HomePage", "Moments", "Recovery", "Vault", "Webdrop", "Work")
    }
}
