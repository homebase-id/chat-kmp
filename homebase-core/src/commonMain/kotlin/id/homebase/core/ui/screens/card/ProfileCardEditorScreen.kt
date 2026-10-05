@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.profile_card_access_allow
import id.homebase.resources.profile_card_not_published
import id.homebase.resources.profile_card_audience_editing
import id.homebase.resources.profile_card_audience_overline
import id.homebase.resources.profile_card_change_design
import id.homebase.resources.profile_card_change_design_short
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

private val ACTION_HEIGHT = ButtonDefaults.MinHeight
private val TOP_BAR_ICON_SLOT = 56.dp
private val COMPACT_HEIGHT = 760.dp
private val WIDE_LAYOUT_MIN_WIDTH = 840.dp
private val NARROW_WIDTH = 400.dp
private val WIDE_PANEL_WIDTH = 420.dp
private val PREVIEW_MAX_WIDTH = 440.dp
private val DESIGN_LABEL_HEIGHT = 32.dp
private val DESIGN_TILE_MIN_WIDTH = 80.dp
private val DESIGN_TILE_MAX_WIDTH = 128.dp
private val DESIGN_TILE_SPACING = 8.dp
private val DESIGN_STRIP_INSET = 20.dp
private val CAPTION_LINE = 20.dp
private val AUDIENCE_AVATAR = 28.dp
private const val CARD_PREVIEW_ASPECT = CARD_THUMB_ASPECT
private const val LARGE_FONT_SCALE = 1.3f

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
        viewModel.onEditorOpened()
        onDispose {
            viewModel.onEditorClosed()
            viewModel.onPreviewDiscarded()
        }
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
                is ProfileCardEvent.ShareImage, ProfileCardEvent.ShareFailed,
                ProfileCardEvent.CircleCardFailed, ProfileCardEvent.CircleCardsUnsupported -> Unit
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
            onRequestDesignAccess = viewModel::onPublishRetry,
        ) { layoutWidth ->
            val backdrop by cardEdgeColor(uiState.cardBottomArgb, uiState.design)
            val designCover by viewModel.designCover.collectAsStateWithLifecycle()
            CardSurface(
                uiState = uiState,
                host = host,
                backdrop = { backdrop },
                onRetry = viewModel::onRetry,
                paintWhileAttached = viewModel::paintWhileAttached,
                modifier = Modifier.fillMaxSize(),
                layoutWidth = layoutWidth,
                cover = designCover,
                coverHeld = uiState.isSwitchingDesign,
                skeleton = true,
            )
        }
        if (uiState.isDesignAccessPromptShown) {
            DesignAccessDialog(onContinue = viewModel::onDesignAccessAccepted, onDismiss = viewModel::onDesignAccessDeclined)
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
    onRequestDesignAccess: () -> Unit = {},
    preview: @Composable BoxScope.(layoutWidth: Dp) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceDim, contentColor = MaterialTheme.colorScheme.onSurface) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH
            val metrics = when {
                wide -> PanelMetrics.Wide
                maxHeight < COMPACT_HEIGHT -> PanelMetrics.Compact
                else -> PanelMetrics.Regular
            }
            val narrow = !wide && maxWidth < NARROW_WIDTH
            // The viewer's sheet width: the preview lays the card out there and scales it down, so it wraps like the saved card.
            val cardWidth = minOf(maxWidth, WIDE_SHEET_MAX_WIDTH)
            val panel: @Composable (Modifier, Shape) -> Unit = { modifier, shape ->
                EditorPanel(
                    uiState = uiState,
                    step = step,
                    onStep = onStep,
                    onSelect = onSelect,
                    onOption = onOption,
                    onBlockOrder = onBlockOrder,
                    onSave = onSave,
                    metrics = metrics,
                    narrow = narrow,
                    shape = shape,
                    modifier = modifier,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EditorTopBar(audience = uiState.selectedAudience, onBack = onBack, onEditProfile = onEditProfile)
                AnimatedVisibility(
                    visible = uiState.showsDesignAccessNote,
                    enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                    exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                ) {
                    DesignAccessNote(
                        onAllow = onRequestDesignAccess,
                        modifier = Modifier.widthIn(max = WIDE_SHEET_MAX_WIDTH).padding(horizontal = 24.dp),
                    )
                }
                if (wide) {
                    // One group, top-aligned: the card and its controls start on the same line.
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.Top,
                    ) {
                        CardPreviewFrame(
                            alignment = Alignment.TopEnd,
                            modifier = Modifier.weight(1f, fill = false).fillMaxHeight(),
                        ) { preview(cardWidth) }
                        panel(Modifier.width(WIDE_PANEL_WIDTH), MaterialTheme.shapes.extraLargeIncreased)
                    }
                } else {
                    CardPreviewFrame(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = metrics.gap),
                    ) { preview(cardWidth) }
                    panel(Modifier.widthIn(max = WIDE_SHEET_MAX_WIDTH).fillMaxWidth(), cardSheetShape())
                }
            }
            // Over the panel, not the card: iOS and desktop draw the native card view above any Compose overlay.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp),
            )
        }
    }
}

/** [body] is shared by both steps so the card above never resizes when the step changes. */
private class PanelMetrics(val body: Dp, val segment: Dp, val gap: Dp, val edge: Dp) {
    companion object {
        val Regular = PanelMetrics(body = 196.dp, segment = 64.dp, gap = 12.dp, edge = 16.dp)
        val Compact = PanelMetrics(body = 172.dp, segment = 60.dp, gap = 8.dp, edge = 12.dp)
        val Wide = PanelMetrics(body = 236.dp, segment = 72.dp, gap = 12.dp, edge = 24.dp)
    }
}

// The card keeps its portrait proportions at any size, so it shrinks on a short screen instead of turning landscape.
@Composable
private fun CardPreviewFrame(
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLargeIncreased
    Box(modifier = modifier, contentAlignment = alignment) {
        Box(
            modifier = Modifier
                .widthIn(max = PREVIEW_MAX_WIDTH)
                .aspectRatio(CARD_PREVIEW_ASPECT, matchHeightConstraintsFirst = true)
                .clip(shape)
                // Sets the card off the page when its own colour is close to the backdrop's.
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            content = content,
        )
    }
}

// The design is saved either way; this says the home page won't follow it until access is allowed.
@Composable
private fun DesignAccessNote(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val height = ButtonDefaults.ExtraSmallContainerHeight
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(MR.string.profile_card_not_published),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            TextButton(
                onClick = onAllow,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.contentPaddingFor(height),
                modifier = Modifier.heightIn(min = height),
            ) {
                Text(stringResource(MR.string.profile_card_access_allow), style = ButtonDefaults.textStyleFor(height))
            }
        }
    }
}

@Composable
private fun EditorTopBar(audience: CardAudience, onBack: () -> Unit, onEditProfile: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(MR.string.menu_back),
            )
        }
        AudienceTitle(
            audience = audience,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = TOP_BAR_ICON_SLOT),
        )
        IconButton(onClick = onEditProfile, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(
                imageVector = Icons.Outlined.ManageAccounts,
                contentDescription = stringResource(MR.string.profile_card_edit_profile),
            )
        }
    }
}

// Read-only: which card is being edited is decided before the editor opens, so it is set as a title, not a chip.
@Composable
private fun AudienceTitle(audience: CardAudience, modifier: Modifier = Modifier) {
    val label = audienceLabel(audience)
    val description = stringResource(MR.string.profile_card_audience_editing, label)
    val isCircle = audience is CardAudience.Circle
    val colors = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Text(
            text = stringResource(MR.string.profile_card_audience_overline),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Box(
                modifier = Modifier
                    .size(AUDIENCE_AVATAR)
                    .background(if (isCircle) colors.tertiaryContainer else colors.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = audienceIcon(audience),
                    contentDescription = null,
                    tint = if (isCircle) colors.onTertiaryContainer else colors.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = if (isCircle) colors.tertiary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun EditorPanel(
    uiState: ProfileCardUiState,
    step: EditorStep,
    onStep: (EditorStep) -> Unit,
    onSelect: (String) -> Unit,
    onOption: (CardOption, String?) -> Unit,
    onBlockOrder: (List<String>) -> Unit,
    onSave: () -> Unit,
    metrics: PanelMetrics,
    narrow: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val fontScale = LocalDensity.current.fontScale
    val design = uiState.design
    val isSaving = uiState.isSavingDesign
    val hasOptions = CardDesignSpecs.of(design)?.options?.isNotEmpty() == true
    val segment = metrics.segment * fontScale.coerceIn(1f, MAX_SEGMENT_GROWTH)
    // Grows with the text so a large font scale shrinks the card rather than clipping the controls.
    val optionsNeeded = CAPTION_LINE * fontScale + metrics.gap + segment + metrics.gap
    val body = maxOf(metrics.body, optionsNeeded + metrics.gap + TOOLBAR_HEIGHT)
    val save: @Composable () -> Unit = {
        SaveFab(isSaving = isSaving, enabled = uiState.canSaveDesign, onClick = onSave)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = shape,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(top = metrics.edge, bottom = metrics.edge),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            StepHeader(
                step = step,
                design = design,
                narrow = narrow,
                showChangeDesign = step == EditorStep.Customise && hasOptions,
                changeDesignEnabled = !isSaving,
                onChangeDesign = { onStep(EditorStep.Design) },
                modifier = Modifier.padding(start = 24.dp, end = 16.dp),
            )
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(motion.defaultSpatialSpec()) { it / 3 * forward } + fadeIn(motion.defaultEffectsSpec()))
                        .togetherWith(slideOutHorizontally(motion.defaultSpatialSpec()) { -it / 3 * forward } + fadeOut(motion.fastEffectsSpec()))
                },
                modifier = Modifier.fillMaxWidth().height(body),
                contentAlignment = Alignment.Center,
            ) { shown ->
                when (shown) {
                    EditorStep.Design -> Column(verticalArrangement = Arrangement.spacedBy(metrics.gap)) {
                        DesignStrip(
                            selected = design,
                            enabled = !isSaving,
                            onSelect = onSelect,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        DesignActions(
                            showSave = uiState.canSaveDesign || isSaving,
                            isSaving = isSaving,
                            canCustomise = !uiState.loadFailed,
                            onSave = onSave,
                            onCustomise = { onStep(EditorStep.Customise) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                    EditorStep.Customise -> CardOptionsPanel(
                        design = design,
                        overrides = uiState.overrides,
                        enabled = !isSaving,
                        onOption = onOption,
                        onBlockOrder = onBlockOrder,
                        segmentHeight = segment,
                        gap = metrics.gap,
                        save = save,
                        onTryAnotherDesign = { if (!isSaving) onStep(EditorStep.Design) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

// Customise leads; Save is the quiet companion here and only becomes the primary action on the customise step.
@Composable
private fun DesignActions(
    showSave: Boolean,
    isSaving: Boolean,
    canCustomise: Boolean,
    onSave: () -> Unit,
    onCustomise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSave) {
            TextButton(
                onClick = { if (!isSaving) onSave() },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.heightIn(min = ACTION_HEIGHT),
                contentPadding = ButtonDefaults.contentPaddingFor(ACTION_HEIGHT),
            ) {
                SaveGlyph(isSaving = isSaving, size = ButtonDefaults.iconSizeFor(ACTION_HEIGHT))
                Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ACTION_HEIGHT)))
                ActionLabel(stringResource(MR.string.save))
            }
        }
        Button(
            onClick = { if (!isSaving) onCustomise() },
            enabled = canCustomise,
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.contentPaddingFor(ACTION_HEIGHT),
            modifier = Modifier.heightIn(min = ACTION_HEIGHT),
        ) {
            ActionLabel(stringResource(MR.string.profile_card_step_customise))
            Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ACTION_HEIGHT)))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.iconSizeFor(ACTION_HEIGHT)),
            )
        }
    }
}

// The glyph and the indicator share one slot, so saving never changes the button's width.
@Composable
private fun SaveGlyph(isSaving: Boolean, size: Dp) {
    Crossfade(isSaving, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), modifier = Modifier.size(size)) { saving ->
        if (saving) {
            LoadingIndicator(modifier = Modifier.size(size), color = LocalContentColor.current)
        } else {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(size))
        }
    }
}

@Composable
internal fun SaveFab(isSaving: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(MR.string.save)
    FilledIconButton(
        onClick = { if (!isSaving) onClick() },
        enabled = enabled || isSaving,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.size(SAVE_FAB_SIZE).semantics { contentDescription = description },
    ) {
        SaveGlyph(isSaving = isSaving, size = 28.dp)
    }
}

@Composable
private fun ActionLabel(label: String) {
    Text(
        text = label,
        style = ButtonDefaults.textStyleFor(ACTION_HEIGHT),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun StepHeader(
    step: EditorStep,
    design: String,
    narrow: Boolean,
    showChangeDesign: Boolean,
    changeDesignEnabled: Boolean,
    onChangeDesign: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val count = stringResource(MR.string.profile_card_step_count, step.ordinal + 1, EditorStep.entries.size)
    val designName = stringResource(designLabel(design))
    val title = when (step) {
        EditorStep.Design -> stringResource(MR.string.profile_card_step_design_title)
        EditorStep.Customise -> stringResource(MR.string.profile_card_step_customise_title, designName)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.heightIn(min = 48.dp)) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInVertically(motion.defaultSpatialSpec()) { it / 2 * forward } + fadeIn(motion.defaultEffectsSpec()))
                    .togetherWith(slideOutVertically(motion.defaultSpatialSpec()) { -it / 2 * forward } + fadeOut(motion.fastEffectsSpec()))
            },
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics {
                    heading()
                    contentDescription = "$title, $count"
                },
        ) { shown ->
            StepTitle(step = shown, designName = designName, narrow = narrow)
        }
        AnimatedVisibility(
            visible = showChangeDesign,
            enter = fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.fastSpatialSpec()),
            exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec()),
        ) {
            val description = stringResource(MR.string.profile_card_change_design)
            val height = ButtonDefaults.ExtraSmallContainerHeight
            FilledTonalButton(
                onClick = onChangeDesign,
                enabled = changeDesignEnabled,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.contentPaddingFor(height),
                modifier = Modifier.padding(start = 8.dp).heightIn(min = height).semantics { contentDescription = description },
            ) {
                Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
                Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(height)))
                Text(stringResource(MR.string.profile_card_change_design_short), style = ButtonDefaults.textStyleFor(height), maxLines = 1)
            }
        }
    }
}

@Composable
private fun StepTitle(step: EditorStep, designName: String, narrow: Boolean) {
    val typography = MaterialTheme.typography
    val large = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    val style = when {
        large -> typography.titleLargeEmphasized
        narrow -> typography.headlineMediumEmphasized
        else -> typography.headlineLargeEmphasized
    }
    when (step) {
        EditorStep.Design -> Text(
            text = stringResource(MR.string.profile_card_step_design_title),
            style = style,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Step two is about the design just chosen, so its name is what the title emphasises.
        EditorStep.Customise -> Column {
            Text(
                text = stringResource(MR.string.profile_card_step_customise),
                style = typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = designName,
                style = style,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DesignStrip(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val designs = CardDesign.all
    val state = rememberCarouselState(initialItem = (designs.indexOf(selected) - 1).coerceAtLeast(0)) { designs.size }
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme
    val labelHeight = DESIGN_LABEL_HEIGHT * LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier = modifier) {
        val byHeight = (maxHeight - labelHeight) * CARD_THUMB_ASPECT
        val count = designs.size
        val fitAll = (maxWidth - DESIGN_STRIP_INSET * 2 - DESIGN_TILE_SPACING * (count - 1)) / count
        // Every design in view when they fit; otherwise the next one shows half, so the strip reads as "keep going".
        val byWidth = if (fitAll >= DESIGN_TILE_MIN_WIDTH) {
            fitAll
        } else {
            val shown = ((maxWidth - DESIGN_STRIP_INSET) / (DESIGN_TILE_MIN_WIDTH + DESIGN_TILE_SPACING)).toInt().coerceAtLeast(1)
            (maxWidth - DESIGN_STRIP_INSET - DESIGN_TILE_SPACING * shown) / (shown + 0.5f)
        }
        val itemWidth = minOf(byHeight, byWidth, DESIGN_TILE_MAX_WIDTH)
        val itemHeight = itemWidth / CARD_THUMB_ASPECT + labelHeight
        val tile: @Composable (Int, Modifier) -> Unit = { index, tileModifier ->
            val design = designs[index]
            DesignTile(
                design = design,
                selected = design == selected,
                enabled = enabled,
                labelHeight = labelHeight,
                onClick = {
                    onSelect(design)
                    scope.launch { state.animateScrollToItem(index, motion.defaultSpatialSpec()) }
                },
                modifier = tileModifier,
            )
        }
        if (fitAll >= DESIGN_TILE_MIN_WIDTH) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DESIGN_TILE_SPACING, Alignment.CenterHorizontally),
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = DESIGN_STRIP_INSET).selectableGroup(),
            ) {
                designs.indices.forEach { tile(it, Modifier.size(itemWidth, itemHeight)) }
            }
        } else {
            HorizontalUncontainedCarousel(
                state = state,
                itemWidth = itemWidth,
                itemSpacing = DESIGN_TILE_SPACING,
                contentPadding = PaddingValues(horizontal = DESIGN_STRIP_INSET),
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(itemHeight).selectableGroup(),
            ) { index -> tile(index, Modifier.fillMaxSize()) }
        }
    }
}

@Composable
private fun DesignTile(
    design: String,
    selected: Boolean,
    enabled: Boolean,
    labelHeight: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    // Selection morphs the tile from a squarer card to a softer one, ringed in primary.
    val corner by animateDpAsState(if (selected) 28.dp else 14.dp, motion.fastSpatialSpec())
    val ring by animateDpAsState(if (selected) 3.dp else 1.dp, motion.fastSpatialSpec())
    val badge by animateFloatAsState(if (selected) 1f else 0f, motion.fastSpatialSpec())
    val shape = RoundedCornerShape(corner)
    val label = stringResource(designLabel(design))
    Column(
        modifier = modifier
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_THUMB_ASPECT)
                .clip(shape)
                .border(BorderStroke(ring, if (selected) colors.primary else colors.outlineVariant), shape),
        ) {
            CardDesignThumbnail(design = design, modifier = Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .graphicsLayer {
                        alpha = badge
                        scaleX = badge
                        scaleY = badge
                    }
                    .padding(8.dp)
                    .size(22.dp)
                    .background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(14.dp))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(labelHeight), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = if (selected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
                color = if (selected) colors.primary else colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
