@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.connectedButtonShapes
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.profile_card_design_save_failed
import id.homebase.resources.profile_card_edit_profile
import id.homebase.resources.profile_card_editor_title
import id.homebase.resources.profile_card_error
import id.homebase.resources.save
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val PREVIEW_PANEL_GAP = 8.dp

@Composable
fun ProfileCardEditorScreen(
    viewModel: ProfileCardViewModel,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val host by viewModel.host.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = getUriHandler()
    val errCard = stringResource(MR.string.profile_card_error)
    val errSave = stringResource(MR.string.profile_card_design_save_failed)

    LaunchedEffect(viewModel) { viewModel.startHost() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onPreviewDiscarded() }
    }
    // Reverting before the pop keeps the viewer from fading in on the unsaved preview.
    val leave = {
        viewModel.onPreviewDiscarded()
        onBack()
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ProfileCardEvent.OpenLink -> uriHandler.openUrl(event.url)
                ProfileCardEvent.DesignSaved -> onBack()
                ProfileCardEvent.DesignSaveFailed -> launch { snackbarHostState.showSnackbar(errSave) }
                ProfileCardEvent.CardFailed -> launch { snackbarHostState.showSnackbar(errCard) }
                is ProfileCardEvent.ShareImage, ProfileCardEvent.ShareFailed -> Unit
            }
        }
    }

    CardExpressiveTheme {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceDim),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = WIDE_SHEET_MAX_WIDTH)
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                    .padding(top = 8.dp),
            ) {
                // No bands here: there is no chrome over the preview to keep clear, and the page clips what doesn't fit its height.
                Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(MaterialTheme.shapes.extraLargeIncreased)) {
                    val backdrop by cardEdgeColor(uiState.cardBottomArgb, uiState.design)
                    CardSurface(
                        uiState = uiState,
                        host = host,
                        backdrop = { backdrop },
                        onRetry = viewModel::onRetry,
                        paintWhileAttached = viewModel::paintWhileAttached,
                        modifier = Modifier.fillMaxSize(),
                    )
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    )
                }
                Spacer(Modifier.height(PREVIEW_PANEL_GAP))
                EditorPanel(
                    design = uiState.design,
                    isSaving = uiState.isSavingDesign,
                    canSave = uiState.canSaveDesign,
                    onBack = leave,
                    onSelect = viewModel::onDesignSelected,
                    onSave = viewModel::onSaveDesign,
                    onEditProfile = onEditProfile,
                )
            }
        }
    }
}

@Composable
private fun EditorPanel(
    design: String,
    isSaving: Boolean,
    canSave: Boolean,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onSave: () -> Unit,
    onEditProfile: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = cardSheetShape(),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(MR.string.menu_back),
                    )
                }
                Text(
                    text = stringResource(MR.string.profile_card_editor_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            DesignPicker(selected = design, enabled = !isSaving, onSelect = onSelect, modifier = Modifier.fillMaxWidth())
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEditProfile) {
                    Text(stringResource(MR.string.profile_card_edit_profile))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onSave, enabled = canSave) {
                    Box(contentAlignment = Alignment.Center) {
                        // Keeps the button's width while the indicator shows.
                        Text(text = stringResource(MR.string.save), modifier = Modifier.alpha(if (isSaving) 0f else 1f))
                        if (isSaving) LoadingIndicator(modifier = Modifier.size(24.dp), color = LocalContentColor.current)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesignPicker(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val designs = CardDesign.all
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        designs.forEachIndexed { index, design ->
            ToggleButton(
                checked = design == selected,
                onCheckedChange = { onSelect(design) },
                enabled = enabled,
                shapes = connectedButtonShapes(index, designs.size),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(designLabel(design)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
