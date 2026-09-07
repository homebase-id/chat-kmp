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
