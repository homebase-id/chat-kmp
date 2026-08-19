package id.homebase.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/**
 * A font family carrying colour emoji glyphs, or null when the platform already supplies them.
 *
 * Android, iOS and desktop resolve emoji through the OS font manager, so they return null and
 * nothing about their typography changes. In the browser Skia has no system fonts to fall back
 * on, so emoji render as missing glyphs unless we ship a font ourselves — the wasm actual
 * returns a bundled Noto Color Emoji (COLRv1).
 *
 * The font is deliberately in `wasmJsMain/composeResources`, not `commonMain`: it is ~4.8 MB
 * and would otherwise be packaged into the Android and iOS bundles for no benefit.
 */
@Composable
expect fun emojiFontFamily(): FontFamily?
