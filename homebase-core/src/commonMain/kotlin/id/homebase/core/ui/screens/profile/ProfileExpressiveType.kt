@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.core.ui.screens.profile

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection

/**
 * The app's typography predates the Expressive styles, so its *Emphasized slots are copies of the
 * plain ones. Here they carry the weight step Expressive defines, and every style follows its own
 * text's direction, so a Latin bio inside an RTL layout keeps its punctuation where it belongs.
 */
@Composable
internal fun ProfileExpressiveType(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val typography = remember(base) { base.emphasized() }
    MaterialExpressiveTheme(
        colorScheme = MaterialTheme.colorScheme,
        motionScheme = MaterialTheme.motionScheme,
        shapes = MaterialTheme.shapes,
        typography = typography,
        content = content,
    )
}

private fun TextStyle.byContent(): TextStyle = copy(textDirection = TextDirection.Content)

private fun TextStyle.heavier(): TextStyle = copy(fontWeight = FontWeight.SemiBold, textDirection = TextDirection.Content)

private fun Typography.emphasized(): Typography = copy(
    displayLarge = displayLarge.byContent(),
    displayMedium = displayMedium.byContent(),
    displaySmall = displaySmall.byContent(),
    headlineLarge = headlineLarge.byContent(),
    headlineMedium = headlineMedium.byContent(),
    headlineSmall = headlineSmall.byContent(),
    titleLarge = titleLarge.byContent(),
    titleMedium = titleMedium.byContent(),
    titleSmall = titleSmall.byContent(),
    bodyLarge = bodyLarge.byContent(),
    bodyMedium = bodyMedium.byContent(),
    bodySmall = bodySmall.byContent(),
    labelLarge = labelLarge.byContent(),
    labelMedium = labelMedium.byContent(),
    labelSmall = labelSmall.byContent(),
    displayLargeEmphasized = displayLarge.heavier(),
    displayMediumEmphasized = displayMedium.heavier(),
    displaySmallEmphasized = displaySmall.heavier(),
    headlineLargeEmphasized = headlineLarge.heavier(),
    headlineMediumEmphasized = headlineMedium.heavier(),
    headlineSmallEmphasized = headlineSmall.heavier(),
    titleLargeEmphasized = titleLarge.heavier(),
    titleMediumEmphasized = titleMedium.heavier(),
    titleSmallEmphasized = titleSmall.heavier(),
    bodyLargeEmphasized = bodyLarge.heavier(),
    bodyMediumEmphasized = bodyMedium.heavier(),
    bodySmallEmphasized = bodySmall.heavier(),
    labelLargeEmphasized = labelLarge.heavier(),
    labelMediumEmphasized = labelMedium.heavier(),
    labelSmallEmphasized = labelSmall.heavier(),
)
