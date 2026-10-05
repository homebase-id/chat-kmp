@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import id.homebase.resources.delete
import id.homebase.resources.profile_card_add_circle_card
import id.homebase.resources.profile_card_add_circle_title
import id.homebase.resources.profile_card_add_circle_hint
import id.homebase.resources.profile_card_add_circle_confirm
import id.homebase.resources.profile_card_add_circle_none
import id.homebase.resources.profile_card_add_circle_load_failed
import id.homebase.resources.profile_card_delete_card
import id.homebase.resources.profile_card_delete_title
import id.homebase.resources.profile_card_delete_message
import id.homebase.resources.profile_card_circle_failed
import id.homebase.resources.profile_card_circle_unsupported
import id.homebase.resources.file_saved_to
import id.homebase.resources.profile_card_audience_circle
import id.homebase.resources.profile_card_audience_description
import id.homebase.resources.profile_card_audience_public
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.RadioButton
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.resources.profile_card_access_body
import id.homebase.resources.profile_card_access_continue
import id.homebase.resources.profile_card_access_later
import id.homebase.resources.profile_card_access_title
import id.homebase.resources.profile_card_add_circle_has_card
import id.homebase.resources.profile_card_add_circle_no_circles
import id.homebase.resources.profile_card_circle_members
import id.homebase.resources.profile_card_not_published
import id.homebase.resources.profile_card_save_public
import id.homebase.resources.profile_card_share_public
import id.homebase.resources.profile_card_share_public_message
import id.homebase.resources.profile_card_share_public_title
import id.homebase.resources.profile_card_save_public_message
import id.homebase.resources.profile_card_save_public_title
import org.jetbrains.compose.resources.pluralStringResource
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
private val AUDIENCE_BADGE_MAX_WIDTH = 160.dp
private val CHROME_CONTROL_SIZE = 40.dp
private val PICKER_ROW_HEIGHT = 64.dp
private const val PICKER_HALF_SHEET_ROWS = 5
private const val DISABLED_ALPHA = 0.38f
private val MAX_SHEET_PULL = 32.dp
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
    val errCircle = stringResource(MR.string.profile_card_circle_failed)
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
                ProfileCardEvent.CircleCardFailed -> launch { snackbarHostState.showSnackbar(errCircle) }
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
                    circleActions = CircleCardActions(
                        onAdd = viewModel::onAddCardClicked,
                        onPick = viewModel::onCircleChosen,
                        onDismissPicker = viewModel::onAddCardDismissed,
                        onDelete = viewModel::onDeleteCardConfirmed,
                    ),
                    onClose = leave,
                    // Over a band the whole strip drags; floating over the card only the handle does, so the card keeps its taps.
                    bandDrag = if (bands.top) dismissDrag else Modifier,
                    handleDrag = if (bands.top) Modifier else dismissDrag,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                CardBottomChrome(
                    isExporting = uiState.isExporting,
                    canShare = uiState.canShare,
                    saveInsteadOfShare = saveInsteadOfShare,
                    onShare = { if (uiState.isCircleSelected) confirmPublicShare = true else viewModel.onShareClicked() },
                    onEdit = onEdit,
                    extraActions = { nfc?.let { CardNfcAction(it) } },
                )
            }
        }
        if (uiState.isDesignAccessPromptShown) {
            DesignAccessDialog(onContinue = viewModel::onDesignAccessAccepted, onDismiss = viewModel::onDesignAccessDeclined)
        }
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
    isExporting: Boolean,
    canShare: Boolean,
    saveInsteadOfShare: Boolean,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    extraActions: @Composable () -> Unit,
) {
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
        HorizontalFloatingToolbar(
            expanded = true,
            floatingActionButton = {
                FloatingToolbarDefaults.VibrantFloatingActionButton(
                    onClick = onEdit,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(MR.string.profile_card_edit),
                    )
                }
            },
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = chromePillColor(),
                toolbarContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            ShareAction(
                isExporting = isExporting,
                enabled = canShare,
                saveInsteadOfShare = saveInsteadOfShare,
                onClick = onShare,
            )
            extraActions()
        }
    }
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
    circleActions: CircleCardActions,
    onClose: () -> Unit,
    bandDrag: Modifier,
    handleDrag: Modifier,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TOP_BAND_HEIGHT)
            .then(bandDrag)
            .semantics {
                isTraversalGroup = true
                traversalIndex = -1f
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
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
        AudienceBadge(
            uiState = uiState,
            onSelect = onSelectCard,
            circleActions = circleActions,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
        )
        ChromeIconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(MR.string.close),
                modifier = Modifier.size(20.dp),
            )
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

internal class CircleCardActions(
    val onAdd: () -> Unit,
    val onPick: (String) -> Unit,
    val onDismissPicker: () -> Unit,
    val onDelete: () -> Unit,
)

@Composable
internal fun AudienceBadge(
    uiState: ProfileCardUiState,
    onSelect: (CardAudience) -> Unit,
    circleActions: CircleCardActions,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selected = uiState.selectedAudience
    val label = audienceLabel(selected)
    val hasMenu = uiState.hasCardMenu
    val description = if (hasMenu) {
        stringResource(MR.string.profile_card_audience_switch, label)
    } else {
        stringResource(MR.string.profile_card_audience_description, label)
    }
    val isCircle = selected is CardAudience.Circle
    val colors = MaterialTheme.colorScheme
    // Same audience colours as the editor's title: a circle card reads tertiary, the public one stays neutral.
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(if (isCircle) colors.tertiaryContainer else chromePillColor(), spec)
    val content by animateColorAsState(if (isCircle) colors.onTertiaryContainer else colors.onSurface, spec)
    Box(modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = (if (hasMenu) Modifier.clickable(role = Role.Button) { expanded = true } else Modifier)
                .clearAndSetSemantics { contentDescription = description }
                .minimumInteractiveComponentSize(),
        ) {
            Surface(shape = CircleShape, color = container, contentColor = content, shadowElevation = 2.dp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(CHROME_CONTROL_SIZE)
                        .widthIn(max = AUDIENCE_BADGE_MAX_WIDTH)
                        .padding(start = 10.dp, end = if (hasMenu) 6.dp else 14.dp),
                ) {
                    Icon(imageVector = audienceIcon(selected), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
                    )
                    if (hasMenu) {
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            uiState.cards.forEach { card ->
                DropdownMenuItem(
                    text = { Text(audienceLabel(card.audience)) },
                    leadingIcon = { Icon(audienceIcon(card.audience), contentDescription = null) },
                    trailingIcon = if (card.audience == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(card.audience)
                    },
                )
            }
            if (uiState.canAddCircleCard) {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.profile_card_add_circle_card)) },
                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    onClick = {
                        expanded = false
                        circleActions.onAdd()
                    },
                )
            }
            if (uiState.isCircleSelected && !uiState.isCardBusy) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.profile_card_delete_card)) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error,
                    ),
                    onClick = {
                        expanded = false
                        confirmDelete = true
                    },
                )
            }
        }
    }
    uiState.circlePicker?.let { picker ->
        CirclePickerSheet(picker = picker, onPick = circleActions.onPick, onDismiss = circleActions.onDismissPicker)
    }
    if (confirmDelete) {
        DeleteCardDialog(
            label = label,
            onDelete = { confirmDelete = false; circleActions.onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
internal fun DeleteCardDialog(label: String, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text(stringResource(MR.string.profile_card_delete_title, label), textAlign = TextAlign.Center) },
            text = { Text(stringResource(MR.string.profile_card_delete_message)) },
            confirmButton = {
                Button(
                    onClick = onDelete,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(MR.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(MR.string.cancel)) }
            },
        )
}

@Composable
private fun CirclePickerSheet(picker: CirclePicker, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AdaptiveSheet(onDismiss = onDismiss, expandFully = picker.circles.size > PICKER_HALF_SHEET_ROWS) {
        CirclePickerContent(
            picker = picker,
            onPick = { id -> dismiss { onPick(id) } },
            onCancel = { dismiss() },
        )
    }
}

@Composable
internal fun CirclePickerContent(picker: CirclePicker, onPick: (String) -> Unit, onCancel: () -> Unit) {
    var chosen by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            text = stringResource(MR.string.profile_card_add_circle_title),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        val message = when {
            picker.loading -> null
            picker.failed -> MR.string.profile_card_add_circle_load_failed
            picker.circles.isEmpty() -> MR.string.profile_card_add_circle_no_circles
            !picker.hasChoice -> MR.string.profile_card_add_circle_none
            else -> MR.string.profile_card_add_circle_hint
        }
        message?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
            )
        }
        when {
            picker.loading -> Box(Modifier.fillMaxWidth().height(PICKER_ROW_HEIGHT * 2), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            picker.circles.isNotEmpty() -> LazyColumn(modifier = Modifier.weight(1f, fill = false).selectableGroup()) {
                items(picker.circles, key = { it.id }) { circle ->
                    CirclePickerRow(
                        circle = circle,
                        hasCard = circle.id in picker.withCard,
                        selected = circle.id == chosen,
                        onSelect = { chosen = circle.id },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, shapes = ButtonDefaults.shapes()) { Text(stringResource(MR.string.cancel)) }
            Button(
                onClick = { chosen?.let(onPick) },
                enabled = chosen != null,
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(MR.string.profile_card_add_circle_confirm))
            }
        }
    }
}

@Composable
private fun CirclePickerRow(circle: CardCircle, hasCard: Boolean, selected: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val muted = colors.onSurface.copy(alpha = DISABLED_ALPHA)
    ListItem(
        headlineContent = { Text(circle.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                if (hasCard) stringResource(MR.string.profile_card_add_circle_has_card)
                else pluralStringResource(MR.plurals.profile_card_circle_members, circle.memberCount, circle.memberCount),
            )
        },
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = !hasCard) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = when {
                hasCard -> muted
                selected -> colors.onSecondaryContainer
                else -> colors.onSurface
            },
            supportingColor = when {
                hasCard -> muted
                selected -> colors.onSecondaryContainer
                else -> colors.onSurfaceVariant
            },
        ),
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.large)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .heightIn(min = PICKER_ROW_HEIGHT)
            .selectable(selected = selected, enabled = !hasCard, role = Role.RadioButton, onClick = onSelect),
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
