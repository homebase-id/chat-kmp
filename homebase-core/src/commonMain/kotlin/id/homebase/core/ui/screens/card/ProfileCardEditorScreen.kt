@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.profile_card_audience_description
import id.homebase.resources.profile_card_design_save_failed
import id.homebase.resources.profile_card_discard_confirm
import id.homebase.resources.profile_card_discard_keep
import id.homebase.resources.profile_card_discard_message
import id.homebase.resources.profile_card_discard_title
import id.homebase.resources.profile_card_edit_profile
import id.homebase.resources.profile_card_error
import id.homebase.resources.profile_card_step_count
import id.homebase.resources.profile_card_step_customise
import id.homebase.resources.profile_card_step_customise_title
import id.homebase.resources.profile_card_step_design_title
import id.homebase.resources.save
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val PREVIEW_PANEL_GAP = 12.dp
private val PREVIEW_SIDE_INSET = 12.dp
private val AUDIENCE_PILL_MAX_WIDTH = 200.dp
private val DESIGN_TILE_MAX_WIDTH = 96.dp
private val PRIMARY_ACTION_HEIGHT = 56.dp
private val COMPACT_HEIGHT = 760.dp
private val OPTION_AREA_HEIGHT = 196.dp
private val OPTION_AREA_HEIGHT_COMPACT = 132.dp
private const val STACK_ACTIONS_FONT_SCALE = 1.3f

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
        ProfileCardEditorContent(
            uiState = uiState,
            step = step,
            onStep = { step = it },
            onBack = requestBack,
            onSelect = viewModel::onDesignSelected,
            onOption = viewModel::onOptionSelected,
            onBlockOrder = viewModel::onBlockOrderChanged,
            onSave = viewModel::onSaveDesign,
            onEditProfile = onEditProfile,
            snackbarHostState = snackbarHostState,
        ) {
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
        }
    }
}

/** Stateless so the capture test can render it around a stand-in for the card. */
@Composable
internal fun ProfileCardEditorContent(
    uiState: ProfileCardUiState,
    step: EditorStep,
    onStep: (EditorStep) -> Unit,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onOption: (CardOption, String?) -> Unit,
    onBlockOrder: (List<String>) -> Unit,
    onSave: () -> Unit,
    onEditProfile: () -> Unit,
    snackbarHostState: SnackbarHostState,
    preview: @Composable BoxScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceDim, contentColor = MaterialTheme.colorScheme.onSurface) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val compact = maxHeight < COMPACT_HEIGHT
            Column(
                modifier = Modifier
                    .widthIn(max = WIDE_SHEET_MAX_WIDTH)
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)),
            ) {
                EditorTopBar(audience = uiState.selectedAudience, onBack = onBack, onEditProfile = onEditProfile)
                // Chrome stays outside the card: iOS and desktop draw the native card view above any Compose overlay.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = PREVIEW_SIDE_INSET)
                        .clip(MaterialTheme.shapes.extraLargeIncreased)
                        // Sets the card off the page when its own colour is close to the backdrop's.
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraLargeIncreased),
                ) {
                    preview()
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
                    onStep = onStep,
                    isSaving = uiState.isSavingDesign,
                    canSave = uiState.canSaveDesign,
                    onSelect = onSelect,
                    onOption = onOption,
                    onBlockOrder = onBlockOrder,
                    onSave = onSave,
                    compact = compact,
                )
            }
        }
    }
}

@Composable
private fun EditorTopBar(audience: CardAudience, onBack: () -> Unit, onEditProfile: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(MR.string.menu_back),
            )
        }
        AudiencePill(audience = audience, modifier = Modifier.align(Alignment.Center))
        IconButton(onClick = onEditProfile, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(
                imageVector = Icons.Outlined.ManageAccounts,
                contentDescription = stringResource(MR.string.profile_card_edit_profile),
            )
        }
    }
}

// Read-only here: which card is being edited is decided before the editor opens.
@Composable
private fun AudiencePill(audience: CardAudience, modifier: Modifier = Modifier) {
    val label = audienceLabel(audience)
    val description = stringResource(MR.string.profile_card_audience_description, label)
    val isCircle = audience is CardAudience.Circle
    Surface(
        shape = CircleShape,
        color = if (isCircle) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (isCircle) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .widthIn(max = AUDIENCE_PILL_MAX_WIDTH)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 36.dp).padding(start = 12.dp, end = 14.dp),
        ) {
            Icon(imageVector = audienceIcon(audience), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
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
    onSelect: (String) -> Unit,
    onOption: (CardOption, String?) -> Unit,
    onBlockOrder: (List<String>) -> Unit,
    onSave: () -> Unit,
    compact: Boolean,
) {
    val motion = MaterialTheme.motionScheme
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = cardSheetShape(),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(top = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepHeader(step = step, design = design, modifier = Modifier.padding(horizontal = 24.dp))
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(motion.defaultSpatialSpec()) { it / 4 * forward } + fadeIn(motion.defaultEffectsSpec()))
                        .togetherWith(slideOutHorizontally(motion.fastSpatialSpec()) { -it / 4 * forward } + fadeOut(motion.fastEffectsSpec()))
                        .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
                },
                contentAlignment = Alignment.TopCenter,
            ) { shown ->
                when (shown) {
                    EditorStep.Design -> DesignPicker(
                        selected = design,
                        enabled = !isSaving,
                        onSelect = onSelect,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    EditorStep.Customise -> CardOptionsPanel(
                        design = design,
                        overrides = overrides,
                        enabled = !isSaving,
                        onOption = onOption,
                        onBlockOrder = onBlockOrder,
                        areaHeight = if (compact) OPTION_AREA_HEIGHT_COMPACT else OPTION_AREA_HEIGHT,
                    )
                }
            }
            // Side by side the labels would be cut at large text sizes; stacked they keep their words.
            val stacked = LocalDensity.current.fontScale >= STACK_ACTIONS_FONT_SCALE
            ActionsLayout(stacked = stacked, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { slot ->
                when (step) {
                    EditorStep.Design -> {
                        if (canSave || isSaving) {
                            SaveButton(isSaving = isSaving, enabled = canSave, primary = false, onClick = onSave, modifier = slot)
                        }
                        Button(
                            onClick = { onStep(EditorStep.Customise) },
                            enabled = !isSaving,
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.contentPaddingFor(PRIMARY_ACTION_HEIGHT),
                            modifier = slot.heightIn(min = PRIMARY_ACTION_HEIGHT),
                        ) {
                            Text(
                                text = stringResource(MR.string.profile_card_step_customise),
                                style = ButtonDefaults.textStyleFor(PRIMARY_ACTION_HEIGHT),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(PRIMARY_ACTION_HEIGHT)))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.iconSizeFor(PRIMARY_ACTION_HEIGHT)),
                            )
                        }
                    }
                    EditorStep.Customise ->
                        SaveButton(
                            isSaving = isSaving,
                            enabled = canSave,
                            primary = true,
                            onClick = onSave,
                            modifier = slot,
                        )
                }
            }
        }
    }
}

@Composable
private fun ActionsLayout(stacked: Boolean, modifier: Modifier, content: @Composable (slot: Modifier) -> Unit) {
    if (stacked) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) { content(Modifier.fillMaxWidth()) }
    } else {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) { content(Modifier.weight(1f)) }
    }
}

@Composable
private fun StepHeader(step: EditorStep, design: String, modifier: Modifier = Modifier) {
    val title = when (step) {
        EditorStep.Design -> stringResource(MR.string.profile_card_step_design_title)
        EditorStep.Customise -> stringResource(MR.string.profile_card_step_customise_title, stringResource(designLabel(design)))
    }
    val count = stringResource(MR.string.profile_card_step_count, step.ordinal + 1, EditorStep.entries.size)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clearAndSetSemantics {
            heading()
            contentDescription = "$title, $count"
        },
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        StepDots(current = step)
    }
}

// The current step's dot stretches into a pill, so progress reads without words.
@Composable
private fun StepDots(current: EditorStep) {
    val motion = MaterialTheme.motionScheme
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 12.dp)) {
        EditorStep.entries.forEach { step ->
            val active = step == current
            val width by animateDpAsState(if (active) 24.dp else 8.dp, motion.defaultSpatialSpec())
            val color by animateColorAsState(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                motion.defaultEffectsSpec(),
            )
            Box(Modifier.size(width = width, height = 8.dp).background(color, CircleShape))
        }
    }
}

@Composable
private fun SaveButton(
    isSaving: Boolean,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val labelAlpha = animateFloatAsState(if (isSaving) 0f else 1f, fade)
    val content: @Composable () -> Unit = {
        Box(contentAlignment = Alignment.Center) {
            // Keeps the button's width while the indicator shows.
            Text(
                text = stringResource(MR.string.save),
                style = ButtonDefaults.textStyleFor(PRIMARY_ACTION_HEIGHT),
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = labelAlpha.value },
            )
            Crossfade(isSaving, animationSpec = fade) { saving ->
                if (saving) LoadingIndicator(modifier = Modifier.size(28.dp), color = LocalContentColor.current)
            }
        }
    }
    val sized = modifier.fillMaxWidth().heightIn(min = PRIMARY_ACTION_HEIGHT)
    val padding = ButtonDefaults.contentPaddingFor(PRIMARY_ACTION_HEIGHT)
    if (primary) {
        Button(onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes(), contentPadding = padding, modifier = sized) { content() }
    } else {
        FilledTonalButton(onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes(), contentPadding = padding, modifier = sized) { content() }
    }
}

@Composable
private fun DesignPicker(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        CardDesign.all.forEach { design ->
            DesignTile(
                design = design,
                selected = design == selected,
                enabled = enabled,
                onClick = { onSelect(design) },
                modifier = Modifier.weight(1f, fill = false).widthIn(max = DESIGN_TILE_MAX_WIDTH),
            )
        }
    }
}

@Composable
private fun DesignTile(
    design: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    // Selection morphs the tile from a squarer card to a softer one, with a ring set off by a gap.
    val corner by animateDpAsState(if (selected) 28.dp else 14.dp, motion.fastSpatialSpec())
    val ringAlpha by animateFloatAsState(if (selected) 1f else 0f, motion.fastEffectsSpec())
    val scale by animateFloatAsState(if (selected) 1f else 0.92f, motion.fastSpatialSpec())
    val label = stringResource(designLabel(design))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(corner + 6.dp))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_THUMB_ASPECT)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .border(3.dp, colors.primary.copy(alpha = ringAlpha), RoundedCornerShape(corner + 5.dp))
                .padding(5.dp)
                .clip(RoundedCornerShape(corner)),
        ) {
            CardDesignThumbnail(design = design, modifier = Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .graphicsLayer { alpha = ringAlpha; scaleX = ringAlpha; scaleY = ringAlpha }
                    .size(22.dp)
                    .background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(14.dp))
            }
        }
        Text(
            text = label,
            style = if (selected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
        )
    }
}

internal enum class EditorStep { Design, Customise }

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
