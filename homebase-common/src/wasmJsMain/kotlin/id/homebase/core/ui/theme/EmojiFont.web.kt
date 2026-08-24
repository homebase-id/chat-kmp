package id.homebase.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import id.homebase.resources.MR
import id.homebase.resources.noto_color_emoji
import org.jetbrains.compose.resources.Font

/**
 * Noto Color Emoji, COLRv1 — vector glyphs, roughly half the size of the bitmap (CBDT) build
 * and sharp at any size. Swapping in the bitmap build is a matter of replacing the file in
 * `wasmJsMain/composeResources/font/` if Skia's COLRv1 support turns out to be insufficient.
 */
@Composable
actual fun emojiFontFamily(): FontFamily? = FontFamily(Font(MR.font.noto_color_emoji))
