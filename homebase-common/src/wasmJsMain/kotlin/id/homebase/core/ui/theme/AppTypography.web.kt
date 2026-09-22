package id.homebase.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import id.homebase.resources.MR
import id.homebase.resources.dejavu_sans
import id.homebase.resources.dejavu_sans_bold
import org.jetbrains.compose.resources.Font

/**
 * DejaVu Sans, chosen on coverage rather than looks: it carries arrows, maths, checkmarks,
 * Greek, Cyrillic, Hebrew and Arabic in one file. Noto Sans — the obvious candidate — has no
 * arrows at all, so it would not have fixed the missing "→" that prompted this.
 *
 * Only Book and Bold exist upstream, so Medium snaps to Book and SemiBold to Bold. Swapping in a
 * family with 500/600 weights is a file replacement plus the [FontFamily] below; nothing else
 * changes. CJK is the known gap — DejaVu has no Han or kana.
 */
@Composable
actual fun appTypography(): Typography = HomebaseTypography.withFontFamily(
    FontFamily(
        Font(MR.font.dejavu_sans, FontWeight.Normal),
        Font(MR.font.dejavu_sans_bold, FontWeight.Bold),
    )
)

private fun Typography.withFontFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)
