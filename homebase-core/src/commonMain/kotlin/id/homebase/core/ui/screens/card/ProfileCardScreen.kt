@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.FilledTonalButton
import id.homebase.resources.ok
import id.homebase.resources.profile_card_more_actions
import id.homebase.resources.profile_card_own_design
import id.homebase.resources.profile_card_public_everyone
import id.homebase.resources.profile_card_read_only
import id.homebase.resources.profile_card_read_only_title
import id.homebase.resources.profile_card_read_only_why
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MenuDefaults
import androidx.compose.ui.semantics.selected
import id.homebase.resources.profile_card_follows_public
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import id.homebase.core.localization.TranslationUtil
import id.homebase.core.util.getUriHandler
import id.homebase.core.util.isDesktopOrWeb
import id.homebase.resources.MR
import id.homebase.resources.profile_edit_load_failed
import id.homebase.resources.profile_edit_retry
import id.homebase.resources.close
import id.homebase.resources.cancel
import id.homebase.resources.profile_card_reset_failed
import id.homebase.resources.profile_card_circle_unsupported
import id.homebase.resources.file_saved_to
import id.homebase.resources.profile_card_audience_circle
import id.homebase.resources.profile_card_audience_description
import id.homebase.resources.profile_card_audience_public
import id.homebase.resources.profile_card_reset_card
import id.homebase.resources.profile_card_reset_title
import id.homebase.resources.profile_card_reset_message
import id.homebase.resources.profile_card_reset_confirm
import id.homebase.resources.profile_card_audience_switch
import id.homebase.resources.profile_card_design_board
import id.homebase.resources.profile_card_design_collage
import id.homebase.resources.profile_card_design_dossier
import id.homebase.resources.profile_card_design_poster
import id.homebase.resources.profile_card_edit
import id.homebase.resources.profile_card_error
import id.homebase.resources.profile_card_save
import id.homebase.resources.profile_card_share
import id.homebase.resources.profile_card_share_failed
import id.homebase.resources.profile_card_unsupported
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import id.homebase.resources.profile_card_access_body
import id.homebase.resources.profile_card_access_continue
import id.homebase.resources.profile_card_access_later
import id.homebase.resources.profile_card_access_title
import id.homebase.resources.profile_card_save_public
import id.homebase.resources.profile_card_share_public
import id.homebase.resources.profile_card_share_public_message
import id.homebase.resources.profile_card_share_public_title
import id.homebase.resources.profile_card_save_public_message
import id.homebase.resources.profile_card_save_public_title
import kotlin.math.exp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// Wide windows get a card-width sheet rather than the page's full website layout.
internal val WIDE_SHEET_MAX_WIDTH = 480.dp

private val TOP_BAND_HEIGHT = 56.dp
private val BAND_FADE_HEIGHT = 16.dp
// The floating toolbar plus its vertical margins.
private val TOOLBAR_BAND_HEIGHT = 88.dp
private val CHROME_GAP = 8.dp
private val CHROME_CONTROL_SIZE = 40.dp
private val MAX_SHEET_PULL = 32.dp
private val MENU_MIN_WIDTH = 224.dp
private val MENU_MAX_WIDTH = 320.dp
private val MENU_INSET = 4.dp
private val MENU_ITEM_CORNER = 12.dp
private val MENU_SELECTED_CORNER = 20.dp
private val MENU_ITEM_SHAPE = RoundedCornerShape(MENU_ITEM_CORNER)
private val DISMISS_DRAG_DISTANCE = 96.dp
private const val DISMISS_FLING_VELOCITY = 1500f
private const val SKELETON_ALPHA_LOW = 0.35f
private const val SKELETON_ALPHA_HIGH = 0.8f
private const val SKELETON_PULSE_MS = 900

@Composable
fun StartCardHostWhenSettled(viewModel: ProfileCardViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        // Building the host (first-use Chromium init + page load) blocks the main thread ~1s; a nav entry is RESUMED only once its enter transition ends.
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        withFrameNanos { }
        viewModel.startHost()
    }
}

@Composable
internal fun CardExpressiveTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}

@Composable
internal fun cardSheetShape(): Shape =
    MaterialTheme.shapes.extraLargeIncreased.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))

// Read the returned state only in draw, so the colour animation redraws instead of recomposing the card.
@Composable
internal fun cardEdgeColor(argb: Int?, design: String): State<Color> =
    animateColorAsState(Color(argb ?: CardDesign.baseArgb(design)), MaterialTheme.motionScheme.defaultEffectsSpec())

@Composable
fun ProfileCardScreen(
    viewModel: ProfileCardViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val host by viewModel.host.collectAsStateWithLifecycle()
    val motion = MaterialTheme.motionScheme
    val snackbarHostState = remember { SnackbarHostState() }
    val fileSystemHandler = getUriHandler()
    // Desktop and web have no share sheet; saving is their way to get the image out.
    val saveInsteadOfShare = remember { isDesktopOrWeb() }
    val nfc = rememberCardNfc()
    val errCard = stringResource(MR.string.profile_card_error)
    val errShare = stringResource(MR.string.profile_card_share_failed)
    val errReset = stringResource(MR.string.profile_card_reset_failed)
    val errCircleUnsupported = stringResource(MR.string.profile_card_circle_unsupported)

    LaunchedEffect(viewModel) { viewModel.onScreenShown() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ProfileCardEvent.OpenLink -> fileSystemHandler.openUrl(event.url)
                is ProfileCardEvent.ShareImage -> {
                    val onError: (Throwable) -> Unit = { e ->
                        Logger.w(tag = "ProfileCard", throwable = e) { "handing the card image to the platform failed" }
                        launch { snackbarHostState.showSnackbar(errShare) }
                    }
                    if (saveInsteadOfShare) {
                        fileSystemHandler.saveFile(
                            file = Path(event.path),
                            suggestedName = event.fileName,
                            onSuccess = { location ->
                                launch {
                                    snackbarHostState.showSnackbar(
                                        TranslationUtil.getString(MR.string.file_saved_to, location),
                                    )
                                }
                            },
                            onError = onError,
                        )
                    } else {
                        fileSystemHandler.shareFile(Path(event.path), onError)
                    }
                }
                ProfileCardEvent.CardFailed -> launch { snackbarHostState.showSnackbar(errCard) }
                ProfileCardEvent.ShareFailed -> launch { snackbarHostState.showSnackbar(errShare) }
                ProfileCardEvent.ResetFailed -> launch { snackbarHostState.showSnackbar(errReset) }
                ProfileCardEvent.CircleCardsUnsupported -> launch { snackbarHostState.showSnackbar(errCircleUnsupported) }
                ProfileCardEvent.DesignSaved, ProfileCardEvent.DesignSaveFailed -> Unit
            }
        }
    }

    val density = LocalDensity.current
    val maxPull = with(density) { MAX_SHEET_PULL.toPx() }
    val dismissDistance = with(density) { DISMISS_DRAG_DISTANCE.toPx() }
    var rawPull by remember { mutableFloatStateOf(0f) }
    // While the sheet moves, a still of the card stands in for the native view, which may not follow a Compose transform.
    var coverHeld by remember { mutableStateOf(false) }
    val holdCover: suspend () -> Unit = {
        if (!coverHeld) {
            viewModel.captureCover()
            coverHeld = true
        }
    }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { scope.launch { viewModel.captureCover() } }
    val dragState = rememberDraggableState { delta -> rawPull = (rawPull + delta).coerceAtLeast(0f) }
    var dragCapture by remember { mutableStateOf<Job?>(null) }
    val dismissDrag = Modifier.draggable(
        state = dragState,
        orientation = Orientation.Vertical,
        onDragStarted = { dragCapture = scope.launch { holdCover() } },
        onDragStopped = { velocity ->
            // Start and stop run as separate coroutines: a capture landing after the release would re-cover the live card.
            dragCapture?.join()
            if (rawPull > dismissDistance || velocity > DISMISS_FLING_VELOCITY) {
                onBack()
            } else {
                animate(rawPull, 0f, animationSpec = motion.fastSpatialSpec()) { value, _ -> rawPull = value }
                coverHeld = false
            }
        },
    )
    var leaving by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch {
                holdCover()
                onBack()
            }
        }
    }
    // System back (incl. predictive back) would otherwise pop straight past holdCover(), leaving
    // the native card view behind on iOS/Desktop instead of following cardSheetExitTransition().
    @Suppress("DEPRECATION") BackHandler { leave() }
    val cover by viewModel.cover.collectAsStateWithLifecycle()
    var confirmPublicShare by rememberSaveable { mutableStateOf(false) }

    CardExpressiveTheme {
        val bands = cardBands(uiState.design)
        val topColor by cardEdgeColor(uiState.cardTopArgb, uiState.design)
        val bottomColor by cardEdgeColor(uiState.cardBottomArgb, uiState.design)
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceDim)) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = WIDE_SHEET_MAX_WIDTH)
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                    .padding(top = 8.dp)
                    // Rubber-banded: the sheet gives a little, and letting go past the threshold hands over to the real pop transition.
                    .graphicsLayer { translationY = maxPull * (1f - exp(-rawPull / maxPull)) }
                    .clip(cardSheetShape())
                    .drawBehind { drawRect(bottomColor) },
            ) {
                CardBandsLayout(
                    bands = bands,
                    topColor = { topColor },
                    bottomColor = { bottomColor },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    CardSurface(
                        uiState = uiState,
                        host = host,
                        backdrop = { bottomColor },
                        onRetry = viewModel::onRetry,
                        paintWhileAttached = viewModel::paintWhileAttached,
                        cover = cover?.image,
                        coverHeld = coverHeld,
                        modifier = Modifier.fillMaxSize(),
                    )
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .then(
                                if (bands.bottom) Modifier
                                else Modifier.navigationBarsPadding().padding(bottom = TOOLBAR_BAND_HEIGHT),
                            )
                            .padding(8.dp),
                    )
                }
                SheetTopChrome(
                    uiState = uiState,
                    onSelectCard = viewModel::onCardSelected,
                    onClose = leave,
                    // Over a band the whole strip drags; floating over the card only the handle does, so the card keeps its taps.
                    bandDrag = if (bands.top) dismissDrag else Modifier,
                    handleDrag = if (bands.top) Modifier else dismissDrag,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                CardBottomChrome(
                    audience = uiState.selectedAudience,
                    isExporting = uiState.isExporting,
                    canShare = uiState.canShare,
                    saveInsteadOfShare = saveInsteadOfShare,
                    readOnly = uiState.isCircleReadOnly,
                    canReset = uiState.canReset,
                    onShare = { if (uiState.isCircleSelected) confirmPublicShare = true else viewModel.onShareClicked() },
                    onEdit = onEdit,
                    onReset = viewModel::onResetCardConfirmed,
                    extraActions = { nfc?.let { CardNfcAction(it) } },
                )
            }
        }
        DesignAccessPrompt(shown = uiState.isDesignAccessPromptShown, viewModel = viewModel)
        val circle = uiState.selectedAudience as? CardAudience.Circle
        if (confirmPublicShare && circle != null) {
            SharePublicCardDialog(
                circleLabel = audienceLabel(circle),
                saveInsteadOfShare = saveInsteadOfShare,
                onConfirm = {
                    confirmPublicShare = false
                    viewModel.onShareClicked()
                },
                onDismiss = { confirmPublicShare = false },
            )
        }
    }
}

// A circle card is never what gets shared, so the toolbar stays the same on every card and the swap is said here, where it happens.
@Composable
internal fun SharePublicCardDialog(circleLabel: String, saveInsteadOfShare: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Public, contentDescription = null) },
        title = {
            Text(
                stringResource(if (saveInsteadOfShare) MR.string.profile_card_save_public_title else MR.string.profile_card_share_public_title),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Text(
                stringResource(
                    if (saveInsteadOfShare) MR.string.profile_card_save_public_message else MR.string.profile_card_share_public_message,
                    circleLabel,
                ),
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(if (saveInsteadOfShare) MR.string.profile_card_save_public else MR.string.profile_card_share_public))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(MR.string.cancel)) }
        },
    )
}

@Composable
internal fun BoxScope.CardBottomChrome(
    audience: CardAudience,
    isExporting: Boolean,
    canShare: Boolean,
    saveInsteadOfShare: Boolean,
    readOnly: Boolean,
    canReset: Boolean,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onReset: () -> Unit,
    extraActions: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var explainReadOnly by remember { mutableStateOf(false) }
    val motion = MaterialTheme.motionScheme
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .semantics {
                isTraversalGroup = true
                traversalIndex = -1f
            }
            .navigationBarsPadding()
            .height(TOOLBAR_BAND_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        // One toolbar: the quick actions as icons, Edit as its labelled, filled lead action.
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = chromePillColor(),
                toolbarContentColor = MaterialTheme.colorScheme.onSurface,
            ),
            contentPadding = PaddingValues(start = 4.dp, end = 8.dp),
        ) {
            ShareAction(
                isExporting = isExporting,
                enabled = canShare,
                saveInsteadOfShare = saveInsteadOfShare,
                onClick = onShare,
            )
            extraActions()
            if (canReset) {
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
                            text = { Text(stringResource(MR.string.profile_card_reset_card)) },
                            leadingIcon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                confirmReset = true
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            // Read-only keeps the slot but turns quiet, so nothing promises an edit that can't be kept.
            AnimatedContent(
                targetState = readOnly,
                transitionSpec = {
                    fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                },
            ) { locked ->
                if (locked) {
                    FilledTonalButton(
                        onClick = { explainReadOnly = true },
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight),
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(MR.string.profile_card_read_only_why), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Button(
                        onClick = onEdit,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight),
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(MR.string.profile_card_edit), maxLines = 1)
                    }
                }
            }
        }
    }
    if (confirmReset) {
        ResetCardDialog(
            audience = audience,
            onReset = { confirmReset = false; onReset() },
            onDismiss = { confirmReset = false },
        )
    }
    if (explainReadOnly) ReadOnlyCardDialog(onDismiss = { explainReadOnly = false })
}

@Composable
internal fun DesignAccessPrompt(shown: Boolean, viewModel: ProfileCardViewModel) {
    if (shown) DesignAccessDialog(onContinue = viewModel::onDesignAccessAccepted, onDismiss = viewModel::onDesignAccessDeclined)
}

@Composable
internal fun DesignAccessDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Public, contentDescription = null) },
        title = { Text(stringResource(MR.string.profile_card_access_title), textAlign = TextAlign.Center) },
        text = { Text(stringResource(MR.string.profile_card_access_body)) },
        confirmButton = {
            Button(onClick = onContinue, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(MR.string.profile_card_access_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(MR.string.profile_card_access_later))
            }
        },
    )
}

// Native bands in the page's colour where a design has content under the chrome; the page has no safe-area support.
private data class CardBands(val top: Boolean, val bottom: Boolean)

private fun cardBands(design: String): CardBands = when (design) {
    CardDesign.POSTER -> CardBands(top = false, bottom = true)
    // Its avatar reaches into the top strip; the bottom is decoration only.
    CardDesign.BOARD -> CardBands(top = true, bottom = false)
    else -> CardBands(top = true, bottom = true)
}

@Composable
private fun CardBandsLayout(
    bands: CardBands,
    topColor: () -> Color,
    bottomColor: () -> Color,
    modifier: Modifier = Modifier,
    card: @Composable BoxScope.() -> Unit,
) {
    Column(modifier = modifier) {
        if (bands.top) {
            Spacer(modifier = Modifier.fillMaxWidth().height(TOP_BAND_HEIGHT).drawBehind { drawRect(topColor()) })
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            card()
            if (bands.top) {
                // Softens the band's straight edge where the page's own artwork runs up into it.
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(BAND_FADE_HEIGHT)
                        .drawBehind {
                            val color = topColor()
                            drawRect(Brush.verticalGradient(listOf(color, color.copy(alpha = 0f))))
                        },
                )
            }
        }
        if (bands.bottom) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind { drawRect(bottomColor()) }
                    .navigationBarsPadding()
                    .height(TOOLBAR_BAND_HEIGHT),
            )
        }
    }
}

@Composable
internal fun SheetTopChrome(
    uiState: ProfileCardUiState,
    onSelectCard: (CardAudience) -> Unit,
    onClose: () -> Unit,
    bandDrag: Modifier,
    handleDrag: Modifier,
    modifier: Modifier = Modifier,
) {
    val gap = with(LocalDensity.current) { CHROME_GAP.roundToPx() }
    val edge = with(LocalDensity.current) { 8.dp.roundToPx() }
    // The audience pill takes all the room up to the close button; the drag handle shows only while it has clear space
    // in the middle, so a long circle name never runs into it.
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .height(TOP_BAND_HEIGHT)
            .then(bandDrag)
            .semantics {
                isTraversalGroup = true
                traversalIndex = -1f
            },
        content = {
            AudienceBadge(uiState = uiState, onSelect = onSelectCard)
            ChromeIconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(MR.string.close),
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 48.dp)
                    .then(handleDrag)
                    // Close already dismisses; a second announced control for the same action is noise.
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                CardChromePill {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                            .size(width = 32.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val close = measurables[1].measure(loose)
        val handle = measurables[2].measure(loose)
        val badgeRoom = (width - edge * 2 - close.width - gap).coerceAtLeast(0)
        val badge = measurables[0].measure(loose.copy(maxWidth = badgeRoom))
        val handleStart = (width - handle.width) / 2
        val handleFits = edge + badge.width + gap <= handleStart
        layout(width, height) {
            badge.placeRelative(edge, (height - badge.height) / 2)
            close.placeRelative(width - edge - close.width, (height - close.height) / 2)
            if (handleFits) handle.placeRelative(handleStart, (height - handle.height) / 2)
        }
    }
}

@Composable
internal fun audienceLabel(audience: CardAudience): String = when (audience) {
    CardAudience.Public -> stringResource(MR.string.profile_card_audience_public)
    is CardAudience.Circle -> audience.label.trim().ifEmpty { stringResource(MR.string.profile_card_audience_circle) }
}

internal fun audienceIcon(audience: CardAudience) =
    if (audience is CardAudience.Circle) Icons.Outlined.Groups else Icons.Outlined.Public

@Composable
internal fun AudienceBadge(
    uiState: ProfileCardUiState,
    onSelect: (CardAudience) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = uiState.selectedAudience
    val label = audienceLabel(selected)
    val hasMenu = uiState.hasCardMenu
    val readOnly = uiState.isCircleReadOnly
    val named = stringResource(if (hasMenu) MR.string.profile_card_audience_switch else MR.string.profile_card_audience_description, label)
    val readOnlyLabel = stringResource(MR.string.profile_card_read_only)
    val description = if (readOnly) "$named $readOnlyLabel" else named
    Box(modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = (if (hasMenu) Modifier.clickable(role = Role.Button) { expanded = true } else Modifier)
                .clearAndSetSemantics { contentDescription = description }
                .minimumInteractiveComponentSize(),
        ) {
            AudienceChip(audience = selected, locked = readOnly, opensMenu = hasMenu)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.widthIn(min = MENU_MIN_WIDTH, max = MENU_MAX_WIDTH).padding(horizontal = MENU_INSET),
        ) {
            uiState.cards.forEach { card ->
                CardMenuItem(
                    card = card,
                    selected = card.audience == selected,
                    onClick = {
                        expanded = false
                        onSelect(card.audience)
                    },
                )
            }
        }
    }
}

/** Public reads secondary, a circle tertiary, wherever a card's audience is shown. */
@Composable
internal fun AudienceChip(
    audience: CardAudience,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    opensMenu: Boolean = false,
    elevated: Boolean = true,
    maxLines: Int = 1,
) {
    val isCircle = audience is CardAudience.Circle
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val container by animateColorAsState(if (isCircle) colors.tertiaryContainer else colors.secondaryContainer, motion.defaultEffectsSpec())
    val content by animateColorAsState(if (isCircle) colors.onTertiaryContainer else colors.onSecondaryContainer, motion.defaultEffectsSpec())
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        shadowElevation = if (elevated) 2.dp else 0.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .animateContentSize(motion.defaultSpatialSpec())
                .heightIn(min = CHROME_CONTROL_SIZE)
                .padding(start = 12.dp, end = if (opensMenu) 6.dp else 16.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Icon(imageVector = audienceIcon(audience), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = audienceLabel(audience),
                style = MaterialTheme.typography.labelLargeEmphasized,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
            )
            if (locked) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(16.dp))
            }
            if (opensMenu) {
                Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// The chosen card fills with its audience's colour, matching the pill that opened the menu.
@Composable
private fun CardMenuItem(card: ProfileCard, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val isCircle = card.audience is CardAudience.Circle
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val fill by animateColorAsState(
        when {
            !selected -> Color.Transparent
            isCircle -> colors.tertiaryContainer
            else -> colors.secondaryContainer
        },
        spec,
    )
    val ink = when {
        !selected -> colors.onSurface
        isCircle -> colors.onTertiaryContainer
        else -> colors.onSecondaryContainer
    }
    val corner by animateDpAsState(if (selected) MENU_SELECTED_CORNER else MENU_ITEM_CORNER, MaterialTheme.motionScheme.fastSpatialSpec())
    val subtitle = when {
        !isCircle -> MR.string.profile_card_public_everyone
        card.isDefault -> MR.string.profile_card_follows_public
        else -> MR.string.profile_card_own_design
    }
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    text = audienceLabel(card.audience),
                    style = if (selected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) ink else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingIcon = { Icon(audienceIcon(card.audience), contentDescription = null) },
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else {
            null
        },
        colors = MenuDefaults.itemColors(textColor = ink, leadingIconColor = ink, trailingIconColor = ink),
        onClick = onClick,
        modifier = Modifier
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(corner))
            .background(fill)
            .semantics { this.selected = selected },
    )
}

// A fixed headline, so a long circle name sits in the body and the dialog keeps one shape.
@Composable
internal fun ResetCardDialog(audience: CardAudience, onReset: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
        title = { Text(stringResource(MR.string.profile_card_reset_title), textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AudienceChip(audience = audience, elevated = false, maxLines = 2)
                Text(stringResource(MR.string.profile_card_reset_message))
            }
        },
        confirmButton = {
            Button(onClick = onReset, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(MR.string.profile_card_reset_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(MR.string.cancel)) }
        },
    )
}

@Composable
internal fun ReadOnlyCardDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(stringResource(MR.string.profile_card_read_only_title), textAlign = TextAlign.Center) },
        text = { Text(stringResource(MR.string.profile_card_circle_unsupported)) },
        confirmButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(MR.string.ok)) }
        },
    )
}

@Composable
private fun chromePillColor(): Color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.88f)

// A tonal pill keeps chrome legible over any design's colours, light or dark.
@Composable
private fun CardChromePill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = chromePillColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
        content = content,
    )
}

@Composable
private fun ChromeIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = chromePillColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 2.dp,
            modifier = Modifier.size(CHROME_CONTROL_SIZE),
        ) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
    }
}

@Composable
internal fun CardSurface(
    uiState: ProfileCardUiState,
    host: CardHost?,
    backdrop: () -> Color,
    onRetry: () -> Unit,
    paintWhileAttached: suspend (onPainted: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    layoutWidth: Dp? = null,
    cover: ImageBitmap? = null,
    coverHeld: Boolean = false,
    skeleton: Boolean = false,
) {
    val motion = MaterialTheme.motionScheme
    // A failure sits on a neutral surface: the design's colour behind an error plate only clashes with it.
    val neutral = MaterialTheme.colorScheme.surfaceContainerHigh
    val failedAny = uiState.loadFailed || uiState.cardFailed
    val ground: () -> Color = { if (failedAny) neutral else backdrop() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Attaching the WebView costs ~300ms of frames on a mid-range phone, which would swallow the enter transition.
    var attached by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycle) {
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        attached = true
    }
    var painted by remember { mutableStateOf(false) }
    LaunchedEffect(attached, host) {
        if (attached && host != null) paintWhileAttached { painted = true }
    }
    val live = painted && uiState.isCardReady

    Box(modifier = modifier.drawBehind { drawRect(ground()) }) {
        if (uiState.loadFailed) {
            CardErrorPlate(
                message = MR.string.profile_edit_load_failed,
                onRetry = onRetry,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        // An unsupported server's /card is its public site, which desktop and web would float over the message.
        if (!uiState.cardUnsupported && attached) {
            host?.let { CardHostView(host = it, modifier = Modifier.fillMaxSize(), layoutWidth = layoutWidth) }
        }
        if (cover != null && !uiState.cardUnsupported) {
            AnimatedVisibility(
                visible = !live || coverHeld,
                enter = fadeIn(motion.fastEffectsSpec()),
                exit = fadeOut(motion.defaultEffectsSpec()),
            ) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val waiting = !live && cover == null
        val failed = uiState.cardFailed
        AnimatedVisibility(
            visible = uiState.cardUnsupported || failed || waiting,
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.slowEffectsSpec()),
            modifier = Modifier.fillMaxSize(),
        ) {
            CardPlaceholder(
                failed = failed,
                unsupported = uiState.cardUnsupported,
                skeletonDesign = uiState.design.takeIf { skeleton },
                backdrop = ground,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun ShareAction(
    isExporting: Boolean,
    enabled: Boolean,
    saveInsteadOfShare: Boolean,
    onClick: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val icon = if (saveInsteadOfShare) Icons.Outlined.Download else Icons.Outlined.Share
    AnimatedContent(
        targetState = isExporting,
        transitionSpec = { fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
    ) { exporting ->
        if (exporting) {
            Box(modifier = Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator(modifier = Modifier.size(40.dp))
            }
        } else {
            IconButton(onClick = onClick, enabled = enabled) {
                Icon(
                    imageVector = icon,
                    contentDescription = stringResource(
                        if (saveInsteadOfShare) MR.string.profile_card_save else MR.string.profile_card_share,
                    ),
                )
            }
        }
    }
}

internal fun designLabel(design: String): StringResource = when (design) {
    CardDesign.POSTER -> MR.string.profile_card_design_poster
    CardDesign.COLLAGE -> MR.string.profile_card_design_collage
    CardDesign.DOSSIER -> MR.string.profile_card_design_dossier
    else -> MR.string.profile_card_design_board
}

@Composable
private fun CardPlaceholder(
    failed: Boolean,
    unsupported: Boolean,
    skeletonDesign: String?,
    backdrop: () -> Color,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().drawBehind { drawRect(backdrop()) }, contentAlignment = Alignment.Center) {
        when {
            unsupported -> Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    text = stringResource(MR.string.profile_card_unsupported),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
            failed -> CardErrorPlate(message = MR.string.profile_card_error, onRetry = onRetry)
            skeletonDesign != null -> CardSkeleton(skeletonDesign)
            else -> ContainedLoadingIndicator()
        }
    }
}

// The chosen design's layout at full size, breathing, so the hero keeps its shape while the card loads.
@Composable
private fun CardSkeleton(design: String) {
    val pulse by rememberInfiniteTransition().animateFloat(
        initialValue = SKELETON_ALPHA_LOW,
        targetValue = SKELETON_ALPHA_HIGH,
        animationSpec = infiniteRepeatable(tween(SKELETON_PULSE_MS), RepeatMode.Reverse),
    )
    CardDesignThumbnail(design = design, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = pulse })
}

// One plate for both preview failures, in the error roles so it reads as an error on any design's colours.
@Composable
private fun CardErrorPlate(message: StringResource, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.padding(16.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(MR.string.profile_edit_retry))
            }
        }
    }
}
