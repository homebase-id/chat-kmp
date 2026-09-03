package id.homebase.auth.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.util.isExpandedLayout

private val FormPaneMaxWidth = 400.dp

// The form pane's width at the smallest window that gets the two-pane layout. Every control below
// is fixed dp, so without scaling off it the form's ink stays put while the window doubles.
private val FormReferenceWidth = 704.dp
private const val MaxFormScale = 1.6f

private val PortraitMarkSize = 64.dp
private val PortraitMarkGap = 32.dp
private val PortraitPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp)

@Composable
internal fun LoginLayout(
    uiState: LoginUiState,
    errorText: String?,
    onAction: (LoginUiAction) -> Unit,
    pendingAuthUrl: String?,
    onContinueAuth: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val large = isExpandedLayout()
        // isExpandedLayout gates on width alone, so a 13" iPad in portrait is large yet has no room
        // for two panes.
        if (large && maxWidth > maxHeight) {
            Row(modifier = Modifier.fillMaxSize()) {
                LoginBrandPanel(
                    identity = uiState.signingInAs,
                    showDomain = !uiState.offeringLastIdentity,
                    modifier = Modifier.weight(0.45f).fillMaxHeight(),
                )
                Surface(
                    modifier = Modifier.weight(0.55f).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                ) {
                    BoxWithConstraints {
                        val scale = (maxWidth / FormReferenceWidth).coerceIn(1f, MaxFormScale)
                        LoginScrollColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            LoginContent(
                                uiState = uiState,
                                errorText = errorText,
                                onAction = onAction,
                                pendingAuthUrl = pendingAuthUrl,
                                onContinueAuth = onContinueAuth,
                                compact = false,
                                scale = scale,
                                modifier = Modifier
                                    .widthIn(max = FormPaneMaxWidth * scale)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        } else {
            val scale =
                if (large) (maxWidth / FormReferenceWidth).coerceIn(1f, MaxFormScale) else 1f
            LoginScrollColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PortraitPadding,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IdentityMark(
                    identity = uiState.signingInAs,
                    size = PortraitMarkSize * scale,
                )
                Spacer(modifier = Modifier.height(PortraitMarkGap * scale))
                LoginContent(
                    uiState = uiState,
                    errorText = errorText,
                    onAction = onAction,
                    pendingAuthUrl = pendingAuthUrl,
                    onContinueAuth = onContinueAuth,
                    // The mark is unlabelled, so the identity has to be named here.
                    compact = true,
                    scale = scale,
                    modifier = Modifier.widthIn(max = FormPaneMaxWidth * scale).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LoginScrollColumn(
    modifier: Modifier,
    contentPadding: PaddingValues,
    horizontalAlignment: Alignment.Horizontal,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Not safeDrawingPadding(): on skiko that helper consumes into a legacy
            // modifier-local nothing downstream reads. safeDrawing's union already carries the
            // ime, so a raised keyboard counts once rather than once per overlapping inset.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}
