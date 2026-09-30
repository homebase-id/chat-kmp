@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.carousel.CarouselItemScope
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.connectedButtonShapes
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.profile_card_audience_editing
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

private const val AUDIENCE_PILL_MAX_FRACTION = 0.7f
private val PRIMARY_ACTION_HEIGHT = 56.dp
private val COMPACT_HEIGHT = 760.dp
private val WIDE_LAYOUT_MIN_WIDTH = 840.dp
private val NARROW_WIDTH = 400.dp
private val WIDE_PANEL_WIDTH = 420.dp
private val PREVIEW_MAX_WIDTH = 440.dp
private val DESIGN_LABEL_HEIGHT = 36.dp
private val DESIGN_TILE_MAX_WIDTH = 128.dp
private const val CARD_PREVIEW_ASPECT = CARD_THUMB_ASPECT
private const val STACK_ACTIONS_FONT_SCALE = 1.3f
private const val SAVING_WEIGHT = 1.15f
private const val CHANGE_DESIGN_WEIGHT = 1.4f
private const val MARQUEE_PAUSE_MS = 2_000

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
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH
            val metrics = when {
                wide -> PanelMetrics.Wide
                maxHeight < COMPACT_HEIGHT -> PanelMetrics.Compact
                else -> PanelMetrics.Regular
            }
            val narrow = !wide && maxWidth < NARROW_WIDTH
            val panel: @Composable (Modifier, Shape) -> Unit = { modifier, shape ->
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
                if (wide) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CardPreviewFrame(modifier = Modifier.weight(1f, fill = false).fillMaxHeight(), content = preview)
                        panel(Modifier.width(WIDE_PANEL_WIDTH), MaterialTheme.shapes.extraLargeIncreased)
                    }
                } else {
                    CardPreviewFrame(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = metrics.gap),
                        content = preview,
                    )
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

private class PanelMetrics(val options: Dp, val toolbar: Dp, val gap: Dp, val edge: Dp) {
    val body: Dp get() = options + gap + toolbar

    companion object {
        val Regular = PanelMetrics(options = 152.dp, toolbar = 64.dp, gap = 12.dp, edge = 16.dp)
        val Compact = PanelMetrics(options = 128.dp, toolbar = 56.dp, gap = 8.dp, edge = 12.dp)
        val Wide = PanelMetrics(options = 216.dp, toolbar = 64.dp, gap = 12.dp, edge = 20.dp)
    }
}

// The card keeps its portrait proportions at any size, so it shrinks on a short screen instead of turning landscape.
@Composable
private fun CardPreviewFrame(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = MaterialTheme.shapes.extraLargeIncreased
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
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

@Composable
private fun EditorTopBar(audience: CardAudience, onBack: () -> Unit, onEditProfile: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(MR.string.menu_back),
            )
        }
        AudiencePill(
            audience = audience,
            modifier = Modifier.align(Alignment.Center).widthIn(max = maxWidth * AUDIENCE_PILL_MAX_FRACTION),
        )
        IconButton(onClick = onEditProfile, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(
                imageVector = Icons.Outlined.ManageAccounts,
                contentDescription = stringResource(MR.string.profile_card_edit_profile),
            )
        }
    }
}

// Read-only here: which card is being edited is decided before the editor opens. A long circle name scrolls rather than hiding its end.
@Composable
private fun AudiencePill(audience: CardAudience, modifier: Modifier = Modifier) {
    val label = audienceLabel(audience)
    val description = stringResource(MR.string.profile_card_audience_editing, label)
    val isCircle = audience is CardAudience.Circle
    Surface(
        shape = CircleShape,
        color = if (isCircle) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isCircle) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 36.dp).padding(start = 12.dp, end = 16.dp),
        ) {
            Icon(imageVector = audienceIcon(audience), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLargeEmphasized,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp).basicMarquee(repeatDelayMillis = MARQUEE_PAUSE_MS),
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
    metrics: PanelMetrics,
    narrow: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = shape,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(top = metrics.edge + 4.dp, bottom = metrics.edge),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            StepHeader(step = step, design = design, modifier = Modifier.padding(horizontal = 24.dp))
            // Both steps fill the same fixed body, so the card above never resizes when the step changes.
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(motion.defaultSpatialSpec()) { it / 3 * forward } + fadeIn(motion.defaultEffectsSpec()))
                        .togetherWith(slideOutHorizontally(motion.defaultSpatialSpec()) { -it / 3 * forward } + fadeOut(motion.fastEffectsSpec()))
                },
                modifier = Modifier.fillMaxWidth().height(metrics.body),
                contentAlignment = Alignment.Center,
            ) { shown ->
                when (shown) {
                    EditorStep.Design -> DesignCarousel(
                        selected = design,
                        enabled = !isSaving,
                        onSelect = onSelect,
                        modifier = Modifier.fillMaxSize(),
                    )
                    EditorStep.Customise -> CardOptionsPanel(
                        design = design,
                        overrides = overrides,
                        enabled = !isSaving,
                        onOption = onOption,
                        onBlockOrder = onBlockOrder,
                        optionsHeight = metrics.options,
                        toolbarHeight = metrics.toolbar,
                        gap = metrics.gap,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            EditorActions(
                step = step,
                isSaving = isSaving,
                canSave = canSave,
                onStep = onStep,
                onSave = onSave,
                narrow = narrow,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun EditorActions(
    step: EditorStep,
    isSaving: Boolean,
    canSave: Boolean,
    onStep: (EditorStep) -> Unit,
    onSave: () -> Unit,
    narrow: Boolean,
    modifier: Modifier = Modifier,
) {
    // Side by side the labels would be cut at large text sizes; stacked they keep their words.
    val stacked = LocalDensity.current.fontScale >= STACK_ACTIONS_FONT_SCALE
    val showSave = step == EditorStep.Customise || canSave || isSaving
    val count = if (showSave) 2 else 1
    // While saving the other action stays in its colours but does nothing, so the screen reads busy rather than broken.
    val secondary: @Composable (Modifier, ButtonShapes) -> Unit = { slot, shapes ->
        when (step) {
            EditorStep.Design -> PrimaryAction(
                label = stringResource(MR.string.profile_card_step_customise),
                trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = { if (!isSaving) onStep(EditorStep.Customise) },
                shapes = shapes,
                modifier = slot,
            )
            EditorStep.Customise -> TonalAction(
                label = stringResource(if (narrow && !stacked) MR.string.profile_card_change_design_short else MR.string.profile_card_change_design),
                leadingIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = { if (!isSaving) onStep(EditorStep.Design) },
                shapes = shapes,
                modifier = slot,
            )
        }
    }
    val save: @Composable (Modifier, ButtonShapes) -> Unit = { slot, shapes ->
        SaveButton(
            isSaving = isSaving,
            enabled = canSave || isSaving,
            primary = step == EditorStep.Customise,
            onClick = { if (!isSaving) onSave() },
            shapes = shapes,
            modifier = slot,
        )
    }
    val saveWeight by animateFloatAsState(if (isSaving) SAVING_WEIGHT else 1f, MaterialTheme.motionScheme.defaultSpatialSpec())
    if (stacked) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val full = Modifier.fillMaxWidth()
            if (step == EditorStep.Design) {
                secondary(full, ButtonDefaults.shapes())
                if (showSave) save(full, ButtonDefaults.shapes())
            } else {
                save(full, ButtonDefaults.shapes())
                secondary(full, ButtonDefaults.shapes())
            }
        }
    } else {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            // The step's own action sits at the end, where the eye lands after the options.
            if (step == EditorStep.Customise) {
                secondary(Modifier.weight(CHANGE_DESIGN_WEIGHT), connectedShapes(0, count))
                save(Modifier.weight(saveWeight), connectedShapes(1, count))
            } else {
                if (showSave) save(Modifier.weight(saveWeight), connectedShapes(0, count))
                secondary(Modifier.weight(1f), connectedShapes(count - 1, count))
            }
        }
    }
}

@Composable
private fun connectedShapes(index: Int, count: Int): ButtonShapes =
    connectedButtonShapes(index, count).let { ButtonShapes(shape = it.shape, pressedShape = it.pressedShape) }

@Composable
private fun PrimaryAction(label: String, trailingIcon: ImageVector, onClick: () -> Unit, shapes: ButtonShapes, modifier: Modifier) {
    Button(
        onClick = onClick,
        shapes = shapes,
        contentPadding = ButtonDefaults.contentPaddingFor(PRIMARY_ACTION_HEIGHT),
        modifier = modifier.heightIn(min = PRIMARY_ACTION_HEIGHT),
    ) {
        ActionLabel(label)
        Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(PRIMARY_ACTION_HEIGHT)))
        Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(PRIMARY_ACTION_HEIGHT)))
    }
}

@Composable
private fun TonalAction(label: String, leadingIcon: ImageVector, onClick: () -> Unit, shapes: ButtonShapes, modifier: Modifier) {
    FilledTonalButton(
        onClick = onClick,
        shapes = shapes,
        contentPadding = PaddingValues(start = 16.dp, end = 20.dp),
        modifier = modifier.heightIn(min = PRIMARY_ACTION_HEIGHT),
    ) {
        Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(PRIMARY_ACTION_HEIGHT)))
        Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(PRIMARY_ACTION_HEIGHT)))
        ActionLabel(label)
    }
}

@Composable
private fun ActionLabel(label: String) {
    Text(
        text = label,
        style = ButtonDefaults.textStyleFor(PRIMARY_ACTION_HEIGHT),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun StepHeader(step: EditorStep, design: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val large = LocalDensity.current.fontScale >= STACK_ACTIONS_FONT_SCALE
    val plain = if (large) typography.titleLarge else typography.headlineMedium
    val emphasized = if (large) typography.titleLargeEmphasized else typography.headlineMediumEmphasized
    val designName = stringResource(designLabel(design))
    val designTitle = stringResource(MR.string.profile_card_step_design_title)
    val customiseTitle = stringResource(MR.string.profile_card_step_customise_title, designName)
    val count = stringResource(MR.string.profile_card_step_count, step.ordinal + 1, EditorStep.entries.size)
    val (title, style) = when (step) {
        EditorStep.Design -> AnnotatedString(designTitle) to emphasized.copy(color = colors.onSurface)
        // The design's name carries the weight, so step two is plainly about the design just chosen.
        EditorStep.Customise -> buildAnnotatedString {
            append(customiseTitle)
            val at = customiseTitle.indexOf(designName)
            if (at >= 0) {
                addStyle(SpanStyle(color = colors.primary, fontWeight = emphasized.fontWeight), at, at + designName.length)
            }
        } to plain.copy(color = colors.onSurfaceVariant)
    }
    val motion = MaterialTheme.motionScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clearAndSetSemantics {
            heading()
            contentDescription = "${title.text}, $count"
        },
    ) {
        AnimatedContent(
            targetState = title to style,
            transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.weight(1f),
        ) { (text, textStyle) ->
            Text(text = text, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
    shapes: ButtonShapes,
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
                if (saving) LoadingIndicator(modifier = Modifier.size(24.dp), color = LocalContentColor.current)
            }
        }
    }
    val sized = modifier.heightIn(min = PRIMARY_ACTION_HEIGHT)
    val padding = ButtonDefaults.contentPaddingFor(PRIMARY_ACTION_HEIGHT)
    if (primary) {
        Button(onClick = onClick, enabled = enabled, shapes = shapes, contentPadding = padding, modifier = sized) { content() }
    } else {
        FilledTonalButton(onClick = onClick, enabled = enabled, shapes = shapes, contentPadding = padding, modifier = sized) { content() }
    }
}

@Composable
private fun DesignCarousel(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val designs = CardDesign.all
    // Opens with the selected design in view but not flush to the edge: its neighbour before it stays on screen.
    val state = rememberCarouselState(initialItem = (designs.indexOf(selected) - 1).coerceAtLeast(0)) { designs.size }
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme
    BoxWithConstraints(modifier = modifier) {
        // Same-size cards that scroll with the last one peeking, so more designs read as "keep going".
        HorizontalUncontainedCarousel(
            state = state,
            itemWidth = minOf((maxHeight - DESIGN_LABEL_HEIGHT) * CARD_THUMB_ASPECT, DESIGN_TILE_MAX_WIDTH),
            itemSpacing = 12.dp,
            contentPadding = PaddingValues(horizontal = 20.dp),
            modifier = Modifier.fillMaxSize().selectableGroup(),
        ) { index ->
            val design = designs[index]
            DesignTile(
                design = design,
                selected = design == selected,
                enabled = enabled,
                onClick = {
                    onSelect(design)
                    scope.launch { state.animateScrollToItem(index, motion.defaultSpatialSpec()) }
                },
            )
        }
    }
}

@Composable
private fun CarouselItemScope.DesignTile(
    design: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    // Selection morphs the tile from a squarer card to a softer one, ringed in primary.
    val corner by animateDpAsState(if (selected) 32.dp else 16.dp, motion.fastSpatialSpec())
    val ring by animateDpAsState(if (selected) 4.dp else 1.dp, motion.fastSpatialSpec())
    val badge by animateFloatAsState(if (selected) 1f else 0f, motion.fastSpatialSpec())
    val shape = RoundedCornerShape(corner)
    val label = stringResource(designLabel(design))
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_THUMB_ASPECT)
                .maskClip(shape)
                .maskBorder(BorderStroke(ring, if (selected) colors.primary else colors.outlineVariant), shape),
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
                    .padding(10.dp)
                    .size(26.dp)
                    .background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            text = label,
            style = if (selected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .height(DESIGN_LABEL_HEIGHT)
                .wrapContentHeight()
                .padding(horizontal = 4.dp),
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
