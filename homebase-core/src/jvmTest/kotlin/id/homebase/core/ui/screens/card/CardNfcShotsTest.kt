package id.homebase.core.ui.screens.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.widget.AdaptiveSheet
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders the NFC panel over a stand-in card, light and dark, to PNGs under CARD_SHOTS_DIR for design review. */
@OptIn(ExperimentalTestApi::class)
class CardNfcShotsTest {

    private val outDir: File? = System.getenv("CARD_SHOTS_DIR")?.let(::File)?.also { it.mkdirs() }

    private class Shot(
        val name: String,
        val panel: CardNfcPanel,
        val canShareInstead: Boolean = true,
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
        val widthDp: Int = REDMI_W,
        val heightDp: Int = REDMI_H,
    )

    private val shots = listOf(
        Shot("nfc-01-android-sharing", CardNfcPanel.AndroidReady),
        Shot("nfc-02-android-nfc-off", CardNfcPanel.AndroidOff),
        Shot("nfc-03-iphone", CardNfcPanel.Iphone),
        Shot("nfc-04-iphone-share-unavailable", CardNfcPanel.Iphone, canShareInstead = false),
        Shot("nfc-05-android-font-scale", CardNfcPanel.AndroidReady, fontScale = 1.6f),
        Shot("nfc-06-iphone-font-scale", CardNfcPanel.Iphone, fontScale = 1.6f),
        Shot("nfc-07-android-rtl", CardNfcPanel.AndroidReady, rtl = true),
        Shot("nfc-08-iphone-small", CardNfcPanel.Iphone, widthDp = 360, heightDp = 640),
        Shot("nfc-09-android-wide", CardNfcPanel.AndroidReady, widthDp = 900, heightDp = 820),
        Shot("nfc-10-iphone-wide", CardNfcPanel.Iphone, widthDp = 900, heightDp = 820),
    )

    @Test
    fun nfcPanelRendersEveryState() {
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
            themed(dark, shot.fontScale, shot.rtl) {
                Box(Modifier.fillMaxSize().background(Color(CardDesign.baseArgb(CardDesign.BOARD))))
                AdaptiveSheet(onDismiss = {}, expandFully = true) {
                    CardNfcPanelContent(panel = shot.panel, onOpenSettings = {}, onShareInstead = {}.takeIf { shot.canShareInstead }, onDialog = isDialog)
                }
            }
        }
        mainClock.advanceTimeBy(SETTLE_MS)
        save(shot.name, dark)
    }

    @Composable
    private fun themed(dark: Boolean, fontScale: Float, rtl: Boolean, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalDensity provides Density(SCALE, fontScale),
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            HomebaseTheme(darkTheme = dark, updatesSystemChrome = false) {
                CardExpressiveTheme(content)
            }
        }
    }

    private fun ComposeUiTest.save(name: String, dark: Boolean) {
        val image = onAllNodes(isRoot()).onFirst().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
        outDir?.let { ImageIO.write(image.toAwtImage(), "png", File(it, "$name-${if (dark) "dark" else "light"}.png")) }
    }

    private companion object {
        const val SCALE = 2f
        const val REDMI_W = 393
        const val REDMI_H = 800
        const val SETTLE_MS = 1_500L
    }
}
