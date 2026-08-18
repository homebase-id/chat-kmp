package id.homebase.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Homebase typography configuration. Converted from Signal's Android XML text styles to Compose
 * Material 3.
 *
 * Line height = fontSize + lineSpacingExtra (from XML)
 */
val HomebaseTypography =
        Typography(
                // Display styles
                displayLarge =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Normal,
                                fontSize = 57.sp,
                                lineHeight = 64.sp,
                                letterSpacing = (-0.25).sp
                        ),
                displayMedium =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Normal,
                                fontSize = 45.sp,
                                lineHeight = 52.sp,
                                letterSpacing = 0.sp
                        ),
                displaySmall =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Normal,
                                fontSize = 36.sp,
                                lineHeight = 44.sp,
                                letterSpacing = 0.sp
                        ),

                // Headline styles (from Signal.Text.HeadlineLarge/Medium/Small)
                headlineLarge =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Normal,
                                fontSize = 32.sp,
                                lineHeight = 40.sp, // 32sp + 8sp
                                letterSpacing = 0.sp
                        ),
                headlineMedium =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Normal,
                                fontSize = 28.sp,
                                lineHeight = 34.sp, // 28sp + 6sp
                                letterSpacing = 0.sp
                        ),
                headlineSmall =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Normal,
                                fontSize = 24.sp,
                                lineHeight = 32.sp,
                                letterSpacing = 0.sp
                        ),

                // Title styles (from Signal.Text.TitleLarge/Medium/Small)
                titleLarge =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Normal,
                                fontSize = 22.sp,
                                lineHeight = 28.sp, // 22sp + 6sp
                                letterSpacing = 0.sp
                        ),
                titleMedium =
                        TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Normal,
                                fontSize = 18.sp,
                                lineHeight = 24.sp, // 18sp + 6sp
                                letterSpacing = 0.15.sp
                        ),
                titleSmall =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp,
                                lineHeight = 22.sp, // 16sp + 6sp
                                letterSpacing = 0.1.sp
                        ),

                // Body styles (from Signal.Text.BodyLarge/Medium/Small)
                bodyLarge =
                        TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Normal,
                                fontSize = 16.sp,
                                lineHeight = 22.sp, // 16sp + 6sp
                                letterSpacing = 0.0125.sp
                        ),
                bodyMedium =
                        TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp,
                                lineHeight = 20.sp, // 14sp + 6sp
                                letterSpacing = 0.01.sp
                        ),
                bodySmall =
                        TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                                lineHeight = 16.sp, // 13sp + 3sp
                                letterSpacing = 0.03.sp
                        ),

                // Label styles (from Signal.Text.LabelLarge/Medium/Small)
                labelLarge =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                lineHeight = 20.sp, // 14sp + 6sp
                                letterSpacing = 0.1.sp
                        ),
                labelMedium =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                lineHeight = 16.sp, // 13sp + 3sp
                                letterSpacing = 0.5.sp
                        ),
                labelSmall =
                        TextStyle(
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp,
                                lineHeight = 15.sp, // 11sp + 4sp
                                letterSpacing = 0.5.sp
                        )
        )

/** Extended text styles for Signal-specific needs (not part of Material 3 Typography) */
object HomebaseTextStyles {
        // Giant text (48sp) - for large numbers or emphasis
        val giant =
                TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 48.sp,
                        lineHeight = 56.sp
                )

        // Caption (12sp + 2sp) - smaller than bodySmall
        val caption =
                TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        letterSpacing = 0.03.sp
                )

        // Material Caption (12sp + 4sp)
        val materialCaption =
                TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        letterSpacing = 0.4.sp
                )

        // Subtitle (13sp)
        val subtitle =
                TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                )

        // Subtitle Bold
        val subtitleBold =
                TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                )

        // Mono text for code/IDs
        val mono =
                TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                )

        // Headline with medium weight
        val headlineMediumBold =
                TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 28.sp,
                        lineHeight = 33.sp // 28sp + 5sp
                )

        // Message Request styles
        val messageRequestTitle =
                TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Normal,
                        fontSize = 20.sp,
                        lineHeight = 28.sp
                )

        val messageRequestSubtitle =
                TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                )

        val messageRequestDescription =
                TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                )

        // Title Large (22sp)
        val titleLarge =
                TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Normal,
                        fontSize = 22.sp,
                        lineHeight = 28.sp
                )

        // Info Card Title (18sp bold)
        val infoCardTitle =
                TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        lineHeight = 24.sp
                )

        // Body Bold variants
        val bodyLargeBold =
                TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                )

        val bodyMediumBold =
                TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                )
}

/**
 * Returns this typography with [family] applied to every text style, or unchanged when
 * [family] is null.
 *
 * Only the web build passes a non-null family (see [emojiFontFamily]), so on every other
 * platform this is identity and typography is untouched.
 */
internal fun Typography.withFontFamily(family: FontFamily?): Typography {
    if (family == null) return this
    fun TextStyle.f() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}
