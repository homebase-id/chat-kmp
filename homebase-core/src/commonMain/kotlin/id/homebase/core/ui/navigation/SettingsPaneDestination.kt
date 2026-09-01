package id.homebase.core.ui.navigation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import id.homebase.core.util.isDesktopOrWeb
import id.homebase.core.util.isExpandedLayout

private val PaneMaxWidth = 980.dp
private val PaneMaxHeight = 800.dp
private const val PaneWidthFraction = 0.92f
private const val PaneHeightFraction = 0.9f

/**
 * Registers [T] as a dialog destination on desktop/web — which is what keeps the screen underneath
 * mounted and visible — and as an ordinary full-screen destination everywhere else.
 *
 * Gated on the platform, not the window width, because `NavHost` rebuilds its graph when the
 * builder lambda changes and that resets the back stack; width is decided inside
 * [SettingsPaneContainer], which falls back to [content] below the expanded breakpoint.
 *
 * [paneContent] must host its own sub-pages rather than pushing routes: a second dialog layer
 * stacks a second platform scrim (`Color.Black` @ 0.6 each, not settable from commonMain) and the
 * app behind goes near-black, which is the thing this pane exists to avoid.
 */
internal inline fun <reified T : Any> NavGraphBuilder.settingsDestination(
    noinline onDismiss: () -> Unit,
    noinline paneContent: @Composable () -> Unit,
    noinline content: @Composable () -> Unit,
) {
    if (isDesktopOrWeb()) {
        dialog<T>(dialogProperties = DialogProperties(usePlatformDefaultWidth = false)) {
            SettingsPaneContainer(
                onDismiss = onDismiss,
                paneContent = paneContent,
                content = content,
            )
        }
    } else {
        composable<T> { content() }
    }
}

@Composable
internal fun SettingsPaneContainer(
    onDismiss: () -> Unit,
    paneContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val floating = isExpandedLayout()
    val focusRequester = remember { FocusRequester() }
    var dismissRequested by remember { mutableStateOf(false) }

    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    // Sizing the surface (rather than a full-bleed wrapper) is what leaves a margin the dialog
    // layer can report as an outside click; a fillMaxSize child would swallow the scrim tap.
    // widthIn must stay OUTSIDE fillMaxWidth: fill reports its own size upward, so an inner
    // widthIn constrains the child and is then ignored. The fraction is what leaves the margin.
    val sizing = if (floating) {
        Modifier
            .widthIn(max = PaneMaxWidth)
            .fillMaxWidth(PaneWidthFraction)
            .heightIn(max = PaneMaxHeight)
            .fillMaxHeight(PaneHeightFraction)
    } else {
        Modifier.fillMaxSize()
    }

    Surface(
        modifier = sizing
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    if (!dismissRequested) {
                        dismissRequested = true
                        onDismiss()
                    }
                    true
                } else {
                    false
                }
            },
        shape = if (floating) MaterialTheme.shapes.extraLarge else RectangleShape,
        color = if (floating) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.background
        },
        tonalElevation = if (floating) 6.dp else 0.dp,
        shadowElevation = if (floating) 6.dp else 0.dp,
    ) {
        if (floating) paneContent() else content()
    }
}
