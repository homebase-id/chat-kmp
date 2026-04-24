package id.homebase.core.ui.screens.defragmenter.ui

import androidx.compose.ui.graphics.Color

/**
 * Win98 chrome + glowing interior palette. Held outside `MaterialTheme` on
 * purpose — this screen is an intentional theme override. Do not plumb these
 * colors into the rest of the app.
 */
object Win98Palette {
    val GrayFace: Color = Color(0xFFC0C0C0)
    val HighlightLight: Color = Color(0xFFFFFFFF)
    val HighlightSoft: Color = Color(0xFFDFDFDF)
    val ShadowDark: Color = Color(0xFF808080)
    val ShadowDarker: Color = Color(0xFF404040)
    val Black: Color = Color(0xFF000000)
    val White: Color = Color(0xFFFFFFFF)

    val Navy: Color = Color(0xFF000080)
    val NavyLight: Color = Color(0xFF1084D0)
    val Teal: Color = Color(0xFF008080)

    // Interior (dark defrag canvas)
    val CanvasBackground: Color = Color(0xFF0A0A12)
    val CanvasBackgroundTop: Color = Color(0xFF101828)
    val GridLine: Color = Color(0xFF1C2234)

    // Glowing block palette — modern remix (used when blocks are tiny).
    val BlockCore: Color = Color(0xFF5EFFB8)
    val BlockEdge: Color = Color(0xFF1E90FF)
    val BlockGlow: Color = Color(0x8000FFCC)
    val TrailStart: Color = Color(0xFF00FFCC)
    val TrailEnd: Color = Color(0x0000FFCC)
    val TargetHalo: Color = Color(0xFFFFD54A)
    val TargetHaloDim: Color = Color(0x33FFD54A)

    // Authentic Win98 defrag.exe palette — used when blocks are large enough
    // to show individual bevels (see DefragGridCanvas CLASSIC_BLOCK_THRESHOLD).
    val ClassicBg: Color = Color(0xFF000000)
    val ClassicBlockFill: Color = Color(0xFF0000A8)
    val ClassicBlockLight: Color = Color(0xFF6464FF)
    val ClassicBlockShadow: Color = Color(0xFF000050)
    val ClassicMoving: Color = Color(0xFFFFFF00)
    val ClassicMovingLight: Color = Color(0xFFFFFFAA)
    val ClassicMovingShadow: Color = Color(0xFFA8A800)

    // Green "defragmented / optimised" palette — used during the Vacuuming
    // and Complete phases for both classic and modern renderers.
    val ClassicGreenFill: Color = Color(0xFF009800)
    val ClassicGreenLight: Color = Color(0xFF50E850)
    val ClassicGreenShadow: Color = Color(0xFF004800)
    val GreenGlowEdge: Color = Color(0xFF00FF88)
}
