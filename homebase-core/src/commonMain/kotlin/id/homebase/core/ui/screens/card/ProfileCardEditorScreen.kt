@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.connectedButtonShapes
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.profile_card_design_save_failed
import id.homebase.resources.profile_card_discard_confirm
import id.homebase.resources.profile_card_discard_keep
import id.homebase.resources.profile_card_discard_message
import id.homebase.resources.profile_card_discard_title
import id.homebase.resources.profile_card_step_customise
import id.homebase.resources.profile_card_step_customise_title
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
    var step by rememberSaveable { mutableStateOf(EditorStep.Design) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val requestBack = {
        when {
            step == EditorStep.Customise -> step = EditorStep.Design
            uiState.hasUnsavedChanges && !uiState.isSavingDesign -> confirmDiscard = true
            else -> leave()
        }
    }
    @Suppress("DEPRECATION") BackHandler { requestBack() }
    if (confirmDiscard) {
        DiscardDialog(onKeep = { confirmDiscard = false }, onDiscard = { confirmDiscard = false; leave() })
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
                    val designCover by viewModel.designCover.collectAsStateWithLifecycle()
                    CardSurface(
                        uiState = uiState,
                        host = host,
                        backdrop = { backdrop },
                        onRetry = viewModel::onRetry,
                        paintWhileAttached = viewModel::paintWhileAttached,
                        modifier = Modifier.fillMaxSize(),
                        cover = designCover,
                        coverHeld = uiState.isSwitchingDesign,
                    )
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    )
                }
                Spacer(Modifier.height(PREVIEW_PANEL_GAP))
                EditorPanel(
                    design = uiState.design,
                    overrides = uiState.overrides,
                    step = step,
                    onStep = { step = it },
                    isSaving = uiState.isSavingDesign,
                    canSave = uiState.canSaveDesign,
                    onBack = requestBack,
                    onSelect = viewModel::onDesignSelected,
                    onOption = viewModel::onOptionSelected,
                    onBlockOrder = viewModel::onBlockOrderChanged,
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
    overrides: CardOverrides,
    step: EditorStep,
    onStep: (EditorStep) -> Unit,
    isSaving: Boolean,
    canSave: Boolean,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onOption: (CardOption, String?) -> Unit,
    onBlockOrder: (List<String>) -> Unit,
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
                    text = when (step) {
                        EditorStep.Design -> stringResource(MR.string.profile_card_editor_title)
                        EditorStep.Customise -> stringResource(MR.string.profile_card_step_customise_title, stringResource(designLabel(design)))
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            when (step) {
                EditorStep.Design ->
                    DesignPicker(selected = design, enabled = !isSaving, onSelect = onSelect, modifier = Modifier.fillMaxWidth())
                EditorStep.Customise -> CardOptionsPanel(
                    design = design,
                    overrides = overrides,
                    enabled = !isSaving,
                    onOption = onOption,
                    onBlockOrder = onBlockOrder,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEditProfile) {
                    Text(stringResource(MR.string.profile_card_edit_profile))
                }
                Spacer(Modifier.weight(1f))
                if (step == EditorStep.Design) {
                    TextButton(onClick = { onStep(EditorStep.Customise) }, enabled = !isSaving) {
                        Text(stringResource(MR.string.profile_card_step_customise))
                    }
                }
                Button(onClick = onSave, enabled = canSave) {
                    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                    val labelAlpha = animateFloatAsState(if (isSaving) 0f else 1f, fade)
                    Box(contentAlignment = Alignment.Center) {
                        // Keeps the button's width while the indicator shows.
                        Text(
                            text = stringResource(MR.string.save),
                            modifier = Modifier.graphicsLayer { alpha = labelAlpha.value },
                        )
                        Crossfade(isSaving, animationSpec = fade) { saving ->
                            if (saving) LoadingIndicator(modifier = Modifier.size(24.dp), color = LocalContentColor.current)
                        }
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

private enum class EditorStep { Design, Customise }

@Composable
private fun DiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(MR.string.profile_card_discard_title)) },
        text = { Text(stringResource(MR.string.profile_card_discard_message)) },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(MR.string.profile_card_discard_confirm)) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(MR.string.profile_card_discard_keep)) } },
    )
}
