package id.homebase.core.ui.screens.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    private val schemed = base.copy(previewOverrides = CardOverrides().with(CardOption.COLOURS, "#1E5B45").with(CardOption.ACCENT, "#F2B84B"))
    private val collageSchemed = base.copy(
        previewDesign = CardDesign.COLLAGE,
        previewOverrides = CardOverrides().with(CardOption.COLOURS, "#DDE5D3"),
    )
    private val dossierSchemed = base.copy(
        previewDesign = CardDesign.DOSSIER,
        previewOverrides = CardOverrides().with(CardOption.COLOURS, "#F4F1EA"),
    )

    private fun appOnly(state: ProfileCardUiState) = state.copy(designAccessMissing = true, designAccessDeclined = true)

    private fun tool(description: String): ComposeUiTest.() -> Unit = {
        // The semantic click reaches a tool scrolled out of view and leaves no hover highlight behind.
        onNodeWithContentDescription(description).performSemanticsAction(SemanticsActions.OnClick)
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
        Shot("23-app-only-design", appOnly(base), EditorStep.Design),
        Shot("24-app-only-customise", appOnly(edited), EditorStep.Customise, act = tool("Social links style")),
        Shot("25-app-only-small-phone", appOnly(edited), EditorStep.Customise, widthDp = 360, heightDp = 640),
        Shot("26-app-only-font-scale", appOnly(edited), EditorStep.Customise, fontScale = 1.6f),
        Shot("27-app-only-long-design", appOnly(base.copy(previewDesign = CardDesign.DOSSIER)), EditorStep.Customise, widthDp = REDMI_W, heightDp = REDMI_H),
        Shot("30-redmi-design", base.copy(previewDesign = CardDesign.DOSSIER), widthDp = REDMI_W, heightDp = REDMI_H),
        Shot("31-redmi-design-access", appOnly(base.copy(previewDesign = CardDesign.DOSSIER)), widthDp = REDMI_W, heightDp = REDMI_H),
        Shot("32-redmi-design-unsaved", appOnly(base.copy(previewDesign = CardDesign.COLLAGE)), widthDp = REDMI_W, heightDp = REDMI_H),
        Shot("33-redmi-colours", schemed, EditorStep.Customise, widthDp = REDMI_W, heightDp = REDMI_H),
        Shot("34-redmi-colours-access", appOnly(schemed), EditorStep.Customise, widthDp = REDMI_W, heightDp = REDMI_H),
        Shot("35-redmi-accent", schemed, EditorStep.Customise, widthDp = REDMI_W, heightDp = REDMI_H, act = tool("Accent colour")),
        Shot("36-redmi-heading-font", schemed, EditorStep.Customise, widthDp = REDMI_W, heightDp = REDMI_H, act = tool("Heading font")),
        Shot("37-redmi-portrait", schemed, EditorStep.Customise, widthDp = REDMI_W, heightDp = REDMI_H, act = tool("Portrait shape")),
        Shot("38-redmi-socials", schemed, EditorStep.Customise, widthDp = REDMI_W, heightDp = REDMI_H, act = tool("Social links style")),
        Shot("39-redmi-order", schemed, EditorStep.Customise, widthDp = REDMI_W, heightDp = REDMI_H, act = tool("Section order")),
        Shot("40-redmi-collage-colours", collageSchemed, EditorStep.Customise, widthDp = REDMI_W, heightDp = REDMI_H),
        Shot("41-redmi-dossier-colours", dossierSchemed, EditorStep.Customise, widthDp = REDMI_W, heightDp = REDMI_H),
        Shot("42-redmi-poster-colours", base.copy(previewDesign = CardDesign.POSTER), EditorStep.Customise, widthDp = REDMI_W, heightDp = REDMI_H),
    )

    private class ViewerShot(
        val name: String,
        val state: ProfileCardUiState,
        val act: ComposeUiTest.() -> Unit = {},
        val popup: Boolean = false,
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
        val widthDp: Int = PHONE_W,
        val heightDp: Int = PHONE_H,
    )

    private val work = CardAudience.Circle("c1", "Work")
    private val emergency = CardAudience.Circle("c3", "Emergency Location Access")
    private val withCircle = base.copy(cards = listOf(public, card(work, CardDesign.COLLAGE)), selectedAudience = work)

    private val viewerShots = listOf(
        ViewerShot("v01-public", base.copy(cards = listOf(public, card(work)))),
        ViewerShot("v02-circle", withCircle),
        ViewerShot("v04-exporting", withCircle.copy(isExporting = true)),
        ViewerShot("v13-long-circle", base.copy(cards = listOf(public, card(emergency)), selectedAudience = emergency)),
        ViewerShot("v14-long-circle-tiny", base.copy(cards = listOf(public, card(longCircle)), selectedAudience = longCircle)),
    )

    private val family = CardAudience.Circle("f", "Family")
    private fun virtual(audience: CardAudience) = card(audience).copy(id = Uuid.NIL)
    private val fixedSet = listOf(public, virtual(family), card(friends, CardDesign.COLLAGE), virtual(work))
    private val fixedFriends = base.copy(cards = fixedSet, selectedAudience = friends)
    private val fixedReadOnly = fixedFriends.copy(circleCardsSupported = false)
    private fun click(description: String): ComposeUiTest.() -> Unit = { onNodeWithContentDescription(description).performClick() }
    private fun clickText(text: String): ComposeUiTest.() -> Unit = { onNodeWithText(text).performClick() }
    private val fixedPublic = base.copy(cards = fixedSet, hasLocalPublicDesign = true)

    private val fixedViewerShots = listOf(
        ViewerShot("k6-v05-read-only", fixedReadOnly),
        ViewerShot("k6-v05b-read-only-why", fixedReadOnly, act = clickText("Why can't I edit?")),
        ViewerShot("k6-v06-single-card", base.copy(cards = listOf(public))),
        ViewerShot("k6-v08-virtual-circle", fixedFriends.copy(selectedAudience = family)),
        ViewerShot("k6-v09-reset-from-toolbar", fixedFriends, act = click("Reset to default design")),
        ViewerShot("k6-v10-public-saved", fixedPublic),
        ViewerShot("k6-v11-font-scale", fixedFriends, fontScale = 1.6f),
        ViewerShot("k6-v12-rtl-read-only", fixedReadOnly, rtl = true),
        ViewerShot("k6-v13-long-circle-small", base.copy(cards = fixedSet + card(longCircle), selectedAudience = longCircle), widthDp = 360, heightDp = 640),
        ViewerShot("k6-v15-public-virtual", base.copy(cards = listOf(virtual(CardAudience.Public)) + fixedSet.drop(1))),
    )

    private val fixedEditorShots = listOf(
        Shot("k6-e01-circle-design", fixedFriends),
        Shot("k6-e02-read-only-design", fixedReadOnly.copy(previewDesign = CardDesign.POSTER)),
        Shot("k6-e03-read-only-customise", fixedReadOnly, EditorStep.Customise),
        Shot("k6-e04-read-only-font-scale", fixedReadOnly, fontScale = 1.6f),
        Shot("k6-e05-read-only-small", fixedReadOnly, widthDp = 360, heightDp = 640),
        Shot("k6-e06-font-scale-design", fixedFriends, fontScale = 1.6f),
        Shot("k6-e07-small-design", fixedFriends, widthDp = 360, heightDp = 640),
        Shot("k6-e08-customise", edited.copy(cards = fixedSet, selectedAudience = friends), EditorStep.Customise),
        Shot("k6-e09-customise-heading", edited.copy(cards = fixedSet, selectedAudience = friends), EditorStep.Customise, act = tool("Heading font")),
        Shot("k6-e10-customise-empty", base.copy(previewDesign = "zine"), EditorStep.Customise),
        Shot("k6-e11-long-circle", base.copy(cards = fixedSet + card(longCircle), selectedAudience = longCircle)),
        Shot("k6-e12-public-unsaved", base.copy(cards = listOf(virtual(CardAudience.Public)) + fixedSet.drop(1), previewDesign = CardDesign.POSTER)),
        Shot("k6-e13-loading", fixedFriends.copy(isCardReady = false), preview = Preview.Loading),
        Shot("k6-e14-load-failed", fixedFriends.copy(loadFailed = true), preview = Preview.LoadFailed),
        Shot("k6-e15-small-font-scale", fixedFriends, fontScale = 1.3f, widthDp = 360, heightDp = 640),
        Shot("k6-e16-small-font-scale-customise", edited.copy(cards = fixedSet, selectedAudience = friends), EditorStep.Customise, fontScale = 1.3f, widthDp = 360, heightDp = 640),
        Shot("k6-e17-customise-order", edited.copy(cards = fixedSet, selectedAudience = friends), EditorStep.Customise, act = tool("Section order")),
        Shot("k6-e18-small-customise", edited.copy(cards = fixedSet, selectedAudience = friends), EditorStep.Customise, widthDp = 360, heightDp = 640),
        Shot("k6-e19-rtl-read-only", fixedReadOnly, rtl = true),
        Shot("k6-e20-rtl-customise", edited.copy(cards = fixedSet, selectedAudience = friends), EditorStep.Customise, rtl = true, act = tool("Section order")),
        Shot("k6-e21-font-scale-order", edited.copy(cards = fixedSet, selectedAudience = friends), EditorStep.Customise, fontScale = 1.6f, act = tool("Section order")),
        Shot("k6-e22-virtual-family", fixedFriends.copy(selectedAudience = family)),
    )

    private fun attribute(type: String, key: String, value: String, visibility: ProfileVisibility, circles: List<String>? = null) = ProfileAttribute(
        id = Uuid.random(),
        type = type,
        versionTag = Uuid.random(),
        visibility = visibility,
        data = JsonObject(mapOf(key to JsonPrimitive(value))),
        acl = AccessControlList(requiredSecurityGroup = visibility.wireValue, circleIdList = circles),
    )

    private val contentCircles = listOf(
        CardCircle(FAMILY_CIRCLE_ID, "Family"),
        CardCircle(FRIENDS_CIRCLE_ID, "Friends"),
        CardCircle(WORK_CIRCLE_ID, "Work"),
    )
    private val contentAttributes = listOf(
        attribute(ProfileAttributeTypes.NAME, ProfileAttributeTypes.KEY_GIVEN_NAME, "Samwise Gamgee", ProfileVisibility.ANONYMOUS),
        attribute(ProfileAttributeTypes.BIO_SUMMARY, ProfileAttributeTypes.KEY_SHORT_BIO, "Gardener, cook, second breakfast enthusiast", ProfileVisibility.ANONYMOUS),
        attribute(ProfileAttributeTypes.PHONE, ProfileAttributeTypes.KEY_PHONE, "+14155550123", ProfileVisibility.CONNECTED, listOf(FRIENDS_CIRCLE_ID)),
        attribute(ProfileAttributeTypes.LINK, ProfileAttributeTypes.KEY_LINK_TARGET, "https://shire.example/rosie", ProfileVisibility.CONNECTED, listOf(FRIENDS_CIRCLE_ID, WORK_CIRCLE_ID)),
        attribute(ProfileAttributeTypes.INSTAGRAM, ProfileAttributeTypes.KEY_INSTAGRAM, "samwise", ProfileVisibility.OWNER),
    )
    private val friendsCard = CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends")
    private val contentBase = base.copy(
        attributes = contentAttributes,
        circles = contentCircles,
        cards = listOf(public, card(friendsCard)),
    )
    private val contentTool = tool("What this card shows")

    private val contentShots = listOf(
        Shot("k9-content-public", contentBase, EditorStep.Customise, widthDp = REDMI_W, heightDp = REDMI_H, act = contentTool),
        Shot("k9-content-friends", contentBase.copy(selectedAudience = friendsCard), EditorStep.Customise, widthDp = REDMI_W, heightDp = REDMI_H, act = contentTool),
        Shot("k9-content-friends-small", contentBase.copy(selectedAudience = friendsCard), EditorStep.Customise, widthDp = 360, heightDp = 640, act = contentTool),
        Shot("k9-content-font-scale", contentBase.copy(selectedAudience = friendsCard), EditorStep.Customise, fontScale = 1.6f, widthDp = REDMI_W, heightDp = REDMI_H, act = contentTool),
        Shot("k9-content-rtl", contentBase.copy(selectedAudience = friendsCard), EditorStep.Customise, rtl = true, widthDp = REDMI_W, heightDp = REDMI_H, act = contentTool),
        Shot("k9-content-empty", base, EditorStep.Customise, widthDp = REDMI_W, heightDp = REDMI_H, act = contentTool),
        Shot("k9-content-wide", contentBase.copy(selectedAudience = friendsCard), EditorStep.Customise, widthDp = 900, heightDp = 820, act = contentTool),
    )

    @Test
    fun contentToolRendersOnThePublicAndACircleCard() {
        for (dark in listOf(false, true)) {
            for (shot in contentShots) render(shot, dark)
        }
    }

    @Test
    fun fixedCircleCardsRenderEveryState() {
        for (dark in listOf(false, true)) {
            for (shot in fixedViewerShots) renderViewer(shot, dark)
            for (shot in fixedEditorShots) render(shot, dark)
            renderPopup("k6-d01-reset-public", dark) { ResetCardDialog(audience = CardAudience.Public, onReset = {}, onDismiss = {}) }
            renderPopup("k6-d02-reset-circle", dark) { ResetCardDialog(audience = friends, onReset = {}, onDismiss = {}) }
            renderPopup("k6-d03-reset-long", dark) { ResetCardDialog(audience = longCircle, onReset = {}, onDismiss = {}) }
            renderPopup("k6-d04-read-only", dark) { ReadOnlyCardDialog(onDismiss = {}) }
        }
    }

    @Test
    fun viewerChromeRendersEveryState() {
        for (dark in listOf(false, true)) {
            for (shot in viewerShots) renderViewer(shot, dark)
            renderPopup("v06-reset-dialog", dark) { ResetCardDialog(audience = work, onReset = {}, onDismiss = {}) }
            renderPopup("v07-access-dialog", dark) { DesignAccessDialog(onContinue = {}, onDismiss = {}) }
            renderPopup("v03-share-public-confirm", dark) {
                SharePublicCardDialog(circleLabel = "Acquaintances", saveInsteadOfShare = false, onConfirm = {}, onDismiss = {})
            }
        }
    }

    @Composable
    private fun themed(dark: Boolean, fontScale: Float = 1f, rtl: Boolean = false, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalDensity provides Density(SCALE, fontScale),
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            HomebaseTheme(darkTheme = dark, updatesSystemChrome = false) {
                CardExpressiveTheme(content)
            }
        }
    }

    private fun renderViewer(shot: ViewerShot, dark: Boolean) = runDesktopComposeUiTest(
        width = (shot.widthDp * SCALE).toInt(),
        height = (shot.heightDp * SCALE).toInt(),
    ) {
        mainClock.autoAdvance = false
        setContent {
            themed(dark, shot.fontScale, shot.rtl) {
                var state by remember { mutableStateOf(shot.state) }
                Box(Modifier.fillMaxSize().background(Color(CardDesign.baseArgb(state.design)))) {
                    SheetTopChrome(
                        uiState = state,
                        onClose = {},
                        bandDrag = Modifier,
                        handleDrag = Modifier,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    CardBottomChrome(
                        audience = state.selectedAudience,
                        isExporting = state.isExporting,
                        canShare = true,
                        saveInsteadOfShare = false,
                        readOnly = state.isCircleReadOnly,
                        showsReset = state.showsReset,
                        canReset = state.canReset,
                        onShare = {},
                        onEdit = {},
                        onReset = {},
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
                        ) { layoutWidth ->
                            when (shot.preview) {
                                Preview.Ready -> StandInCard(shot.state.design, shot.state.overrides.palette, Modifier.fillMaxSize().laidOutAt(layoutWidth))
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

    // Real-size type laid out at the viewer's width, so the shot shows the preview's scale-down, not a reflow.
    @Composable
    private fun StandInCard(design: String, palette: CardPalette?, modifier: Modifier) {
        val name = "Samwise Gamgeex"
        val headline = "HOMEBASE / NEW IDENTITY OWNER"
        val link = "samwise.gamgee.demo.rocks/posts"
        fun hex(value: String) = Color(hexToArgb(value))
        val ink = palette?.ink?.let(::hex) ?: Color(CardDesign.inkArgb(design))
        val ground = palette?.ground?.let(::hex) ?: Color(CardDesign.baseArgb(design))
        Column(
            modifier = modifier.background(ground).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(120.dp).background(ink.copy(alpha = 0.3f)))
            Text(name, style = MaterialTheme.typography.displaySmall, color = ink)
            Text(headline, style = MaterialTheme.typography.labelLarge, color = ink.copy(alpha = 0.7f))
            Text(link, style = MaterialTheme.typography.bodyLarge, color = ink.copy(alpha = 0.7f))
            palette?.accent?.let { Box(Modifier.size(width = 160.dp, height = 8.dp).background(hex(it))) }
        }
    }

    private companion object {
        const val SCALE = 2f
        const val PHONE_W = 412
        const val PHONE_H = 892
        const val REDMI_W = 393
        const val REDMI_H = 800
        const val SETTLE_MS = 1_500L
    }
}
