package id.homebase.core.ui.theme

import androidx.compose.ui.graphics.Color

/** Light theme colors for Homebase (Signal-based) */
object LightColors {
    // Material 3 Core Colors
    val Primary = Color(0xFF2C58C3)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFD2DFFB)
    val OnPrimaryContainer = Color(0xFF051845)

    val Secondary = Color(0xFF586071)
    val OnSecondary = Color(0xFFFFFFFF)
    val SecondaryContainer = Color(0xFFDCE5F9)
    val OnSecondaryContainer = Color(0xFF151D2C)

    val Background = Color(0xFFFBFCFF)
    val OnBackground = Color(0xFF1B1D1D)

    val Surface = Color(0xFFFBFCFF)
    val OnSurface = Color(0xFF1B1B1D)
    val SurfaceVariant = Color(0xFFE7EBF3)
    val OnSurfaceVariant = Color(0xFF545863)
    val OnSurfaceVariant1 = Color(0xFFBBBFC8)

    val Error = Color(0xFFBA1B1B)
    val OnError = Color(0xFFFFFFFF)
    val ErrorContainer = Color(0xFFFFDAD4)
    val OnErrorContainer = Color(0xFF410002)

    val Outline = Color(0xFF808389)

    // Surface Elevation Variants
    val Surface1 = Color(0xFFF2F5F9)
    val Surface2 = Color(0xFFEDF0F6)
    val Surface3 = Color(0xFFE8ECF4)
    val Surface4 = Color(0xFFE6EAF3)
    val Surface5 = Color(0xFFE3E7F1)

    // Transparent Colors
    val Transparent1 = Color(0x14FFFFFF)
    val Transparent2 = Color(0x29FFFFFF)
    val Transparent3 = Color(0x8FFFFFFF)
    val Transparent4 = Color(0xB8FFFFFF)
    val Transparent5 = Color(0xF5FFFFFF)

    // Neutral Colors
    val Neutral = Color(0xFFFFFFFF)
    val NeutralVariant = Color(0xB8FFFFFF)
    val NeutralSurface = Color(0x99FFFFFF)

    // Inverse Transparent Colors
    val TransparentInverse1 = Color(0x0A000000)
    val TransparentInverse2 = Color(0x14000000)
    val TransparentInverse3 = Color(0x66000000)
    val TransparentInverse4 = Color(0xB8000000)
    val TransparentInverse5 = Color(0xE0000000)

    // Inverse Neutral Colors
    val NeutralInverse = Color(0xFF121212)
    val NeutralVariantInverse = Color(0xFF5C5C5C)

    // Custom Colors (for overlays, etc.)
    val OnCustom = Color(0xFFFFFFFF)
    val OnCustomVariant = Color(0xB3FFFFFF)
}

/** Dark theme colors for Homebase (Signal-based) */
object DarkColors {
    // Material 3 Core Colors
    val Primary = Color(0xFFB6C5FA)
    val OnPrimary = Color(0xFF1E2438)
    val PrimaryContainer = Color(0xFF464B5C)
    val OnPrimaryContainer = Color(0xFFDBE1FC)

    val Secondary = Color(0xFFC1C6DD)
    val OnSecondary = Color(0xFF2A3042)
    val SecondaryContainer = Color(0xFF414659)
    val OnSecondaryContainer = Color(0xFFDCE1F9)

    val Background = Color(0xFF1B1C1F)
    val OnBackground = Color(0xFFE2E1E5)

    val Surface = Color(0xFF1B1C1F)
    val OnSurface = Color(0xFFE2E1E5)
    val SurfaceVariant = Color(0xFF303133)
    val OnSurfaceVariant = Color(0xFFBEBFC5)
    val OnSurfaceVariant1 = Color(0xFF4D5059)

    val Error = Color(0xFFFFB4A9)
    val OnError = Color(0xFF690005)
    val ErrorContainer = Color(0xFF930006)
    val OnErrorContainer = Color(0xFFFFDAD6)

    val Outline = Color(0xFF5C5E65)

    // Surface Elevation Variants
    val Surface1 = Color(0xFF23242A)
    val Surface2 = Color(0xFF272A31)
    val Surface3 = Color(0xFF2C2F37)
    val Surface4 = Color(0xFF2E3039)
    val Surface5 = Color(0xFF31343E)

    // Transparent Colors
    val Transparent1 = Color(0x0AFFFFFF)
    val Transparent2 = Color(0x1FFFFFFF)
    val Transparent3 = Color(0x29FFFFFF)
    val Transparent4 = Color(0x7AFFFFFF)
    val Transparent5 = Color(0xB8FFFFFF)

    // Neutral Colors
    val Neutral = Color(0xFF121212)
    val NeutralVariant = Color(0xFF5C5C5C)
    val NeutralSurface = Color(0x14FFFFFF)

    // Inverse Transparent Colors
    val TransparentInverse1 = Color(0x0A000000)
    val TransparentInverse2 = Color(0x14000000)
    val TransparentInverse3 = Color(0x29000000)
    val TransparentInverse4 = Color(0xB8000000)
    val TransparentInverse5 = Color(0xF5000000)

    // Inverse Neutral Colors
    val NeutralInverse = Color(0xE0FFFFFF)
    val NeutralVariantInverse = Color(0xA3FFFFFF)

    // Custom Colors (for overlays, etc.)
    val OnCustom = Color(0xFFFFFFFF)
    val OnCustomVariant = Color(0xB3FFFFFF)

    // Alpha Variants
    val Background92 = Color(0xEB1B1C1F)
    val Surface87 = Color(0xDE1B1C1F)
    val SurfaceVariant92 = Color(0xEB303133)
}

/** Extended color palette for custom UI elements */
object ExtendedColors {
    // Common colors used in both themes
    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)
    val Transparent = Color(0x00000000)

    // Status colors
    val Success = Color(0xFF4CAF50)
    val Warning = Color(0xFFFF9800)
    val Info = Color(0xFF2196F3)
}
