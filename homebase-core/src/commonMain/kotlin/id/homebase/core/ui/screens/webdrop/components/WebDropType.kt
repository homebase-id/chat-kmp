@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.core.ui.screens.webdrop.components

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import id.homebase.core.ui.screens.profile.ProfileExpressiveType

/**
 * Expressive weights, text direction from the content (so "7 days" never reads "days 7" under an
 * RTL layout), and wrapped lines anchored to the layout's start edge rather than the paragraph's.
 */
@Composable
internal fun WebDropType(content: @Composable () -> Unit) {
    ProfileExpressiveType {
        val align = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left
        val base = MaterialTheme.typography
        val typography = remember(base, align) { base.anchored(align) }
        MaterialExpressiveTheme(
            colorScheme = MaterialTheme.colorScheme,
            motionScheme = MaterialTheme.motionScheme,
            shapes = MaterialTheme.shapes,
            typography = typography,
            content = content,
        )
    }
}

private fun Typography.anchored(align: TextAlign): Typography {
    fun TextStyle.a() = copy(textAlign = align)
    return copy(
        displayLarge = displayLarge.a(), displayMedium = displayMedium.a(), displaySmall = displaySmall.a(),
        headlineLarge = headlineLarge.a(), headlineMedium = headlineMedium.a(), headlineSmall = headlineSmall.a(),
        titleLarge = titleLarge.a(), titleMedium = titleMedium.a(), titleSmall = titleSmall.a(),
        bodyLarge = bodyLarge.a(), bodyMedium = bodyMedium.a(), bodySmall = bodySmall.a(),
        labelLarge = labelLarge.a(), labelMedium = labelMedium.a(), labelSmall = labelSmall.a(),
        displayLargeEmphasized = displayLargeEmphasized.a(),
        displayMediumEmphasized = displayMediumEmphasized.a(),
        displaySmallEmphasized = displaySmallEmphasized.a(),
        headlineLargeEmphasized = headlineLargeEmphasized.a(),
        headlineMediumEmphasized = headlineMediumEmphasized.a(),
        headlineSmallEmphasized = headlineSmallEmphasized.a(),
        titleLargeEmphasized = titleLargeEmphasized.a(),
        titleMediumEmphasized = titleMediumEmphasized.a(),
        titleSmallEmphasized = titleSmallEmphasized.a(),
        bodyLargeEmphasized = bodyLargeEmphasized.a(),
        bodyMediumEmphasized = bodyMediumEmphasized.a(),
        bodySmallEmphasized = bodySmallEmphasized.a(),
        labelLargeEmphasized = labelLargeEmphasized.a(),
        labelMediumEmphasized = labelMediumEmphasized.a(),
        labelSmallEmphasized = labelSmallEmphasized.a(),
    )
}
