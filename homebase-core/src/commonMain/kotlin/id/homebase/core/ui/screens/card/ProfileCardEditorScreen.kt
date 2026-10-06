@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import id.homebase.resources.profile_card_more_actions
import id.homebase.resources.profile_card_read_only
import id.homebase.resources.profile_card_read_only_line
import id.homebase.resources.profile_card_design_locked_title
import androidx.compose.material3.FilledIconButton
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.profile_card_access_allow_description
import id.homebase.resources.profile_card_app_only
import id.homebase.resources.profile_card_change_design_to
import id.homebase.resources.profile_card_audience_editing
import id.homebase.resources.profile_card_design_save_failed
import id.homebase.resources.profile_card_discard_confirm
import id.homebase.resources.profile_card_discard_keep
import id.homebase.resources.profile_card_discard_message
import id.homebase.resources.profile_card_discard_title
import id.homebase.resources.profile_card_edit_profile
import id.homebase.resources.profile_card_circle_unsupported
import id.homebase.resources.profile_card_error
import id.homebase.resources.profile_card_step_count
import id.homebase.resources.profile_card_step_customise
import id.homebase.resources.profile_card_step_design_title
import id.homebase.resources.save
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val ACTION_HEIGHT = ButtonDefaults.MinHeight
private val APP_ONLY_LABEL_MIN_ROOM = 216.dp
private val COMPACT_HEIGHT = 760.dp
private val WIDE_LAYOUT_MIN_WIDTH = 840.dp
private val WIDE_PANEL_WIDTH = 420.dp
private val PREVIEW_MAX_WIDTH = 440.dp
private val PREVIEW_SIDE_INSET = 16.dp
private val DESIGN_LABEL_HEIGHT = 24.dp
private val DESIGN_TILE_MIN_WIDTH = 80.dp
private val DESIGN_TILE_MAX_WIDTH = 128.dp
private val DESIGN_TILE_SPACING = 12.dp
private val DESIGN_STRIP_INSET = 20.dp
private val CAPTION_LINE = 20.dp
// The card keeps at least the rest of the height, so the controls never shrink it out of being the hero.
private const val PANEL_MAX_SHARE = 0.55f
private const val LOCKED_TILE_ALPHA = 0.38f
private const val UNSELECTED_TILE_SCALE = 0.92f
private const val LARGE_TILE_GROWTH = 1.25f
// Tiles kept in view before the selected one, so the strip opens on its first design whenever the choice allows.
private const val PEEK_LEAD = 2

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
    val errCircleUnsupported = stringResource(MR.string.profile_card_circle_unsupported)

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
                ProfileCardEvent.CircleCardsUnsupported -> launch { snackbarHostState.showSnackbar(errCircleUnsupported) }
                is ProfileCardEvent.ShareImage, ProfileCardEvent.ShareFailed, ProfileCardEvent.ResetFailed -> Unit
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
        DesignAccessPrompt(shown = uiState.isDesignAccessPromptShown, viewModel = viewModel)
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
            // The viewer's sheet width: the preview lays the card out there and scales it down, so it wraps like the saved card.
            val cardWidth = minOf(maxWidth, WIDE_SHEET_MAX_WIDTH)
            val panelMaxHeight = maxHeight * PANEL_MAX_SHARE
            // A read-only card has nothing to customise, so it stays on the design step.
            val shownStep = if (uiState.isCircleReadOnly) EditorStep.Design else step
            val panel: @Composable (Modifier, Shape) -> Unit = { modifier, shape ->
                EditorPanel(
                    uiState = uiState,
                    step = shownStep,
                    onStep = onStep,
                    onSelect = onSelect,
                    onOption = onOption,
                    onBlockOrder = onBlockOrder,
                    onSave = onSave,
                    onAppOnly = onRequestDesignAccess,
                    metrics = metrics,
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
                EditorTopBar(
                    audience = uiState.selectedAudience,
                    readOnly = uiState.isCircleReadOnly,
                    onBack = onBack,
                    onEditProfile = onEditProfile,
                )
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
                            keepProportions = true,
                            alignment = Alignment.TopEnd,
                            modifier = Modifier.weight(1f, fill = false).fillMaxHeight(),
                        ) { preview(cardWidth) }
                        panel(Modifier.width(WIDE_PANEL_WIDTH), MaterialTheme.shapes.extraLargeIncreased)
                    }
                } else {
                    CardPreviewFrame(
                        keepProportions = false,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = PREVIEW_SIDE_INSET, vertical = metrics.gap),
                    ) { preview(cardWidth) }
                    panel(
                        Modifier.widthIn(max = WIDE_SHEET_MAX_WIDTH).fillMaxWidth().heightIn(max = panelMaxHeight),
                        cardSheetShape(),
                    )
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
private class PanelMetrics(val body: Dp, val gap: Dp, val edge: Dp) {
    companion object {
        val Regular = PanelMetrics(body = 144.dp, gap = 8.dp, edge = 16.dp)
        val Compact = PanelMetrics(body = 144.dp, gap = 8.dp, edge = 8.dp)
        val Wide = PanelMetrics(body = 236.dp, gap = 12.dp, edge = 24.dp)
    }
}

/**
 * On a phone the card is drawn at nearly the viewer's own width and runs past the frame's bottom, where it scrolls as it
 * does in the viewer; shrinking it to fit made a poster of it. A wide window has the height to show it whole.
 */
@Composable
private fun CardPreviewFrame(
    keepProportions: Boolean,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLargeIncreased
    Box(modifier = modifier, contentAlignment = alignment) {
        Box(
            modifier = Modifier
                .widthIn(max = PREVIEW_MAX_WIDTH)
                .then(
                    if (keepProportions) Modifier.aspectRatio(CARD_THUMB_ASPECT, matchHeightConstraintsFirst = true)
                    else Modifier.fillMaxSize(),
                )
                .clip(shape)
                // Sets the card off the page when its own colour is close to the backdrop's.
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            content = content,
        )
    }
}

@Composable
private fun EditorTopBar(
    audience: CardAudience,
    readOnly: Boolean,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val editing = stringResource(MR.string.profile_card_audience_editing, audienceLabel(audience))
    val readOnlyLabel = stringResource(MR.string.profile_card_read_only)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(MR.string.menu_back),
                )
            }
            // The viewer's chip, static here: which card is edited is decided before the editor opens.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .clearAndSetSemantics { contentDescription = if (readOnly) "$editing. $readOnlyLabel" else editing },
            ) {
                AudienceChip(audience = audience, locked = readOnly, elevated = false)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(MR.string.profile_card_more_actions))
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = MaterialTheme.shapes.large,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.string.profile_card_edit_profile)) },
                        leadingIcon = { Icon(Icons.Outlined.ManageAccounts, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEditProfile()
                        },
                    )
                }
            }
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
    onAppOnly: () -> Unit,
    metrics: PanelMetrics,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val fontScale = LocalDensity.current.fontScale
    val design = uiState.design
    val isSaving = uiState.isSavingDesign
    val grow = fontScale.coerceIn(1f, LARGE_TEXT_SCALE)
    // Grows with the text so a large font scale shortens the preview rather than clipping the controls.
    val optionsNeeded = CAPTION_LINE * fontScale + CHIP_HEIGHT * grow + 8.dp + metrics.gap * 2 + TOOLBAR_HEIGHT
    val body = maxOf(metrics.body, optionsNeeded)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = shape,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(top = metrics.edge, bottom = metrics.edge),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            if (uiState.isCircleReadOnly) ReadOnlyNotice(Modifier.padding(horizontal = 16.dp))
            StepHeader(
                step = step,
                design = design,
                isSaving = isSaving,
                canSave = uiState.canSaveDesign,
                canCustomise = !uiState.loadFailed,
                readOnly = uiState.isCircleReadOnly,
                showsAppOnly = uiState.showsAppOnlyTag,
                onAppOnly = onAppOnly,
                onSave = onSave,
                onStep = onStep,
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp),
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
                    EditorStep.Design -> DesignStrip(
                        selected = design,
                        enabled = !isSaving && !uiState.isCircleReadOnly && !uiState.loadFailed,
                        locked = uiState.isCircleReadOnly,
                        dimmed = uiState.isCircleReadOnly || uiState.loadFailed,
                        onSelect = onSelect,
                        modifier = Modifier.fillMaxSize(),
                    )
                    EditorStep.Customise -> CardOptionsPanel(
                        design = design,
                        overrides = uiState.overrides,
                        enabled = !isSaving,
                        onOption = onOption,
                        onBlockOrder = onBlockOrder,
                        gap = metrics.gap,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

// One filled action per step: Customise on the first, Save on the second; Save on the first is the quiet way out.
@Composable
private fun StepHeader(
    step: EditorStep,
    design: String,
    isSaving: Boolean,
    canSave: Boolean,
    canCustomise: Boolean,
    readOnly: Boolean,
    showsAppOnly: Boolean,
    onAppOnly: () -> Unit,
    onSave: () -> Unit,
    onStep: (EditorStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val count = stringResource(MR.string.profile_card_step_count, step.ordinal + 1, EditorStep.entries.size)
    val designName = stringResource(designLabel(design))
    val save: () -> Unit = { if (!isSaving) onSave() }
    AnimatedContent(
        targetState = step,
        transitionSpec = {
            val forward = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (slideInVertically(motion.defaultSpatialSpec()) { it / 2 * forward } + fadeIn(motion.defaultEffectsSpec()))
                .togetherWith(slideOutVertically(motion.defaultSpatialSpec()) { -it / 2 * forward } + fadeOut(motion.fastEffectsSpec()))
        },
        contentAlignment = Alignment.CenterStart,
        modifier = modifier,
    ) { shown ->
        if (shown == EditorStep.Design) {
            DesignStepHeader(
                designName = designName,
                count = count,
                canCustomise = canCustomise,
                readOnly = readOnly,
                onCustomise = { if (!isSaving) onStep(EditorStep.Customise) },
            )
            return@AnimatedContent
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            when (shown) {
                EditorStep.Design -> Unit
                // The design's name is the way back to step one, so the step reads as "this design, adjusted".
                EditorStep.Customise -> {
                    val description = stringResource(MR.string.profile_card_change_design_to, designName)
                    BoxWithConstraints(Modifier.weight(1f)) {
                        // Below this the label would squeeze the design's name, so the tag keeps only its icon.
                        val tagLabelled = maxWidth >= APP_ONLY_LABEL_MIN_ROOM * LocalDensity.current.fontScale.coerceAtLeast(1f)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            FilledTonalButton(
                                onClick = { if (!isSaving) onStep(EditorStep.Design) },
                                enabled = !isSaving,
                                shapes = ButtonDefaults.shapes(),
                                contentPadding = ButtonDefaults.contentPaddingFor(ACTION_HEIGHT),
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .heightIn(min = ACTION_HEIGHT)
                                    .semantics {
                                        heading()
                                        contentDescription = "$description, $count"
                                    },
                            ) {
                                Icon(
                                    Icons.Outlined.SwapHoriz,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.iconSizeFor(ACTION_HEIGHT)),
                                )
                                Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ACTION_HEIGHT)))
                                ActionLabel(designName)
                            }
                            if (showsAppOnly) {
                                AppOnlyTag(
                                    labelled = tagLabelled,
                                    onClick = { if (!isSaving) onAppOnly() },
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                    Button(
                        onClick = save,
                        enabled = canSave || isSaving,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.contentPaddingFor(ACTION_HEIGHT),
                        modifier = Modifier.heightIn(min = ACTION_HEIGHT),
                    ) {
                        SaveGlyph(isSaving = isSaving, size = ButtonDefaults.iconSizeFor(ACTION_HEIGHT))
                        Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ACTION_HEIGHT)))
                        ActionLabel(stringResource(MR.string.save))
                    }
                }
            }
        }
    }
}

// Customise is the step's one action and leads on to Save. Large text keeps it inline as an icon, so the title keeps its line
// and the card keeps its height.
@Composable
private fun DesignStepHeader(
    designName: String,
    count: String,
    canCustomise: Boolean,
    readOnly: Boolean,
    onCustomise: () -> Unit,
) {
    val compact = LocalDensity.current.fontScale >= LARGE_TEXT_SCALE
    val title = if (readOnly) {
        stringResource(MR.string.profile_card_design_locked_title, designName)
    } else {
        stringResource(MR.string.profile_card_step_design_title)
    }
    val customise = stringResource(MR.string.profile_card_step_customise)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = ACTION_HEIGHT),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(
                minFontSize = MaterialTheme.typography.titleSmall.fontSize,
                maxFontSize = MaterialTheme.typography.titleLargeEmphasized.fontSize,
            ),
            modifier = Modifier.weight(1f).semantics {
                heading()
                contentDescription = "$title, $count"
            },
        )
        when {
            readOnly -> Unit
            compact -> WithTooltip(customise) {
                FilledIconButton(
                    onClick = onCustomise,
                    enabled = canCustomise,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = customise)
                }
            }
            else -> Button(
                onClick = onCustomise,
                enabled = canCustomise,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.contentPaddingFor(ACTION_HEIGHT),
                modifier = Modifier.heightIn(min = ACTION_HEIGHT),
            ) {
                ActionLabel(customise)
                Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ACTION_HEIGHT)))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.iconSizeFor(ACTION_HEIGHT)),
                )
            }
        }
    }
}

@Composable
private fun ReadOnlyNotice(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text = stringResource(MR.string.profile_card_read_only_line),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
            )
        }
    }
}

// Quiet on purpose: the app-only card is a choice the owner made, not an error, and the tag is the way back to the grant.
@Composable
private fun AppOnlyTag(labelled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(MR.string.profile_card_app_only)
    val action = stringResource(MR.string.profile_card_access_allow_description)
    val colors = MaterialTheme.colorScheme
    val semantics = Modifier.semantics {
        contentDescription = label
        onClick(label = action) { onClick(); true }
    }
    if (!labelled) {
        IconButton(onClick = onClick, modifier = modifier.then(semantics)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        }
        return
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
        },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = colors.onSurfaceVariant,
            leadingIconContentColor = colors.onSurfaceVariant,
        ),
        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = colors.outlineVariant),
        modifier = modifier.then(semantics),
    )
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
private fun ActionLabel(label: String) {
    Text(
        text = label,
        style = ButtonDefaults.textStyleFor(ACTION_HEIGHT),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DesignStrip(
    selected: String,
    enabled: Boolean,
    locked: Boolean,
    dimmed: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val designs = CardDesign.all
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (designs.indexOf(selected) - PEEK_LEAD).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val labelHeight = DESIGN_LABEL_HEIGHT * fontScale
    // Large text widens the tiles rather than cutting their names; the strip then scrolls with a peek.
    val minTile = DESIGN_TILE_MIN_WIDTH * fontScale.coerceAtMost(LARGE_TILE_GROWTH)
    BoxWithConstraints(modifier = modifier) {
        val byHeight = (maxHeight - labelHeight) * CARD_THUMB_ASPECT
        val count = designs.size
        val fitAll = (maxWidth - DESIGN_STRIP_INSET * 2 - DESIGN_TILE_SPACING * (count - 1)) / count
        // Every design in view when they fit; otherwise the next one shows half, so the strip reads as "keep going".
        val byWidth = if (fitAll >= minTile) {
            fitAll
        } else {
            val shown = ((maxWidth - DESIGN_STRIP_INSET) / (minTile + DESIGN_TILE_SPACING)).toInt().coerceAtLeast(1)
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
                locked = locked,
                dimmed = dimmed,
                labelHeight = labelHeight,
                onClick = {
                    onSelect(design)
                    scope.launch { state.animateScrollToItem((index - PEEK_LEAD).coerceAtLeast(0)) }
                },
                modifier = tileModifier,
            )
        }
        if (fitAll >= minTile) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DESIGN_TILE_SPACING, Alignment.CenterHorizontally),
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = DESIGN_STRIP_INSET).selectableGroup(),
            ) {
                designs.indices.forEach { tile(it, Modifier.size(itemWidth, itemHeight)) }
            }
        } else {
            LazyRow(
                state = state,
                horizontalArrangement = Arrangement.spacedBy(DESIGN_TILE_SPACING),
                contentPadding = PaddingValues(horizontal = DESIGN_STRIP_INSET),
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(itemHeight).selectableGroup(),
            ) {
                items(designs.size, key = { designs[it] }) { index -> tile(index, Modifier.size(itemWidth, itemHeight)) }
            }
        }
    }
}

@Composable
private fun DesignTile(
    design: String,
    selected: Boolean,
    enabled: Boolean,
    locked: Boolean,
    dimmed: Boolean,
    labelHeight: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    // Selection springs the tile up to full size and softens its corners, the same way a picked swatch morphs.
    val corner by animateDpAsState(if (selected) 32.dp else 12.dp, motion.fastSpatialSpec())
    val scale by animateFloatAsState(if (selected) 1f else UNSELECTED_TILE_SCALE, motion.defaultSpatialSpec())
    val ring by animateDpAsState(if (selected) 3.dp else 1.dp, motion.fastSpatialSpec())
    val badge by animateFloatAsState(if (selected) 1f else 0f, motion.fastSpatialSpec())
    val dim = if (dimmed && !selected) LOCKED_TILE_ALPHA else 1f
    val shape = RoundedCornerShape(corner)
    val label = stringResource(designLabel(design))
    val labelStyle = if (selected) typography.labelLargeEmphasized else typography.labelLarge
    Column(
        modifier = modifier
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_THUMB_ASPECT)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = dim
                }
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
                        rotationZ = (1f - badge) * -90f
                    }
                    .padding(8.dp)
                    .size(26.dp)
                    .background(if (locked) colors.secondary else colors.primary, MaterialShapes.Cookie4Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (locked) Icons.Outlined.Lock else Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (locked) colors.onSecondary else colors.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(labelHeight).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = labelStyle,
                color = (if (selected) colors.primary else colors.onSurfaceVariant).copy(alpha = dim),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = typography.labelSmall.fontSize, maxFontSize = labelStyle.fontSize),
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
