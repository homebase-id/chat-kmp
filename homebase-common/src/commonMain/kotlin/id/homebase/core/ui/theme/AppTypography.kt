package id.homebase.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable

/**
 * [HomebaseTypography], with a bundled text font applied on platforms that have no system fonts.
 *
 * Android, iOS and desktop resolve text through the OS font manager, so their actuals return
 * [HomebaseTypography] unchanged. In the browser Skia has no system font manager at all
 * (`WebFont.kt` loads every typeface through an empty `FontMgr.default`), so anything outside the
 * minimal default face — arrows, maths, checkmarks, Greek, Cyrillic — renders as missing glyphs.
 */
@Composable
expect fun appTypography(): Typography
