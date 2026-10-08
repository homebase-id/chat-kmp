package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PhoneNumberFieldRtlTest {

    private val outDir: File? = System.getenv("CARD_SHOTS_DIR")?.let(::File)?.also { it.mkdirs() }

    private fun render(direction: LayoutDirection, check: (fieldCenter: Float, label: Float, error: Float, prefix: Float) -> Unit) =
        runDesktopComposeUiTest(width = 400, height = 200) {
            setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    MaterialTheme {
                        Surface {
                            PhoneNumberField(
                                e164Value = "+14155550123",
                                onValueChange = {},
                                label = "Phone",
                                isError = true,
                                errorText = "Not a phone number",
                                modifier = Modifier.padding(16.dp).width(360.dp),
                            )
                        }
                    }
                }
            }
            waitForIdle()
            outDir?.let { ImageIO.write(onNode(isRoot()).captureToImage().toAwtImage(), "png", File(it, "phone-field-$direction.png")) }
            fun centerX(text: String) = onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
            check(200f * density.density, centerX("Phone"), centerX("Not a phone number"), centerX("+1"))
        }

    @Test
    fun rtlLabelErrorAndPrefixFollowTheLayout() = render(LayoutDirection.Rtl) { mid, label, error, prefix ->
        assertTrue(label > mid, "label should sit at the start (right) in RTL")
        assertTrue(error > mid, "error should sit at the start (right) in RTL")
        assertTrue(prefix > mid, "dial-code prefix should sit at the start (right) in RTL")
    }

    @Test
    fun ltrLabelErrorAndPrefixSitOnTheLeft() = render(LayoutDirection.Ltr) { mid, label, error, prefix ->
        assertTrue(label < mid)
        assertTrue(error < mid)
        assertTrue(prefix < mid)
    }
}
