@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.ui.draw.shadow



import androidx.compose.material3.IconButtonDefaults

import androidx.compose.material3.FilledTonalIconButton

import id.homebase.core.ui.screens.contactbook.rememberNewCircleAction
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material3.MenuDefaults
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.runtime.rememberUpdatedState
import id.homebase.resources.profile_card_add_card
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LocalContentColor
import id.homebase.core.ui.theme.withEmojiFont
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.FilledTonalButton
import id.homebase.resources.profile_card_read_only_ok
import id.homebase.resources.profile_card_read_only
import id.homebase.resources.profile_card_not_saved
import id.homebase.resources.profile_card_read_only_title
import id.homebase.resources.profile_card_read_only_why
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
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
import id.homebase.core.util.isExpandedLayout
import id.homebase.resources.MR
import id.homebase.resources.profile_edit_load_failed
import id.homebase.resources.profile_edit_retry
import id.homebase.resources.close
import id.homebase.resources.collapse
import id.homebase.resources.expand
import id.homebase.resources.cancel
import id.homebase.resources.profile_card_circle_unsupported
import id.homebase.resources.file_saved_to
import id.homebase.resources.profile_card_audience_circle
import id.homebase.resources.profile_card_audience_description
import id.homebase.resources.profile_card_audience_public
import id.homebase.resources.profile_card_design_board
import id.homebase.resources.profile_card_design_collage
import id.homebase.resources.profile_card_design_dossier
import id.homebase.resources.profile_card_design_links
import id.homebase.resources.profile_card_design_poster
import id.homebase.resources.edit
import id.homebase.resources.profile_card_menu_edit_desc
import id.homebase.resources.profile_card_menu_design_desc
import id.homebase.resources.profile_card_design_action
import id.homebase.resources.profile_card_error
import id.homebase.resources.profile_card_save
import id.homebase.resources.profile_card_share
import id.homebase.resources.profile_card_share_failed
import id.homebase.resources.profile_card_unsupported
import id.homebase.resources.profile_card_save_public
import id.homebase.resources.profile_card_share_public
import id.homebase.resources.profile_card_share_public_message
import id.homebase.resources.profile_card_share_public_title
import id.homebase.resources.profile_card_save_public_message
import id.homebase.resources.profile_card_save_public_title
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import id.homebase.core.widget.WithTooltip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import id.homebase.core.widget.LocalOpenOverlays
import id.homebase.core.widget.ProvideOpenOverlays
import id.homebase.core.widget.ReportOverlay
import id.homebase.core.ui.navigation.LocalPaneEntered

// Wide windows get a card-width sheet rather than the page's full website layout.
internal val WIDE_SHEET_MAX_WIDTH = 480.dp
// Past the site's 768 CSS px breakpoint, so the wide page shows, but no wider than it reads well.
private val DESKTOP_PAGE_MAX_WIDTH = 1024.dp

private val TOP_BAND_HEIGHT = 56.dp
// The share FAB plus its vertical margins.
private val FAB_BAND_HEIGHT = 88.dp
private val CHROME_GAP = 8.dp
private val CHROME_CONTROL_SIZE = 40.dp
private val MENU_MIN_WIDTH = 248.dp
private val MENU_MAX_WIDTH = 320.dp
private val MENU_INSET = 4.dp
private val MENU_ITEM_CORNER = 12.dp
private val MENU_SELECTED_CORNER = 20.dp
private val MENU_ITEM_SHAPE = RoundedCornerShape(MENU_ITEM_CORNER)
private val MINI_CARD_WIDTH = 26.dp
private val MINI_CARD_CORNER = 5.dp
private val MINI_CARD_SELECTED_CORNER = 9.dp
private val ZeroCorner = CornerSize(0.dp)
private const val SHEET_HEIGHT_FRACTION = 0.93f
private const val SCRIM_ALPHA = 0.32f
private const val UNSAVED_LABEL_ALPHA = 0.8f
private const val SKELETON_ALPHA_LOW = 0.35f
private const val SKELETON_ALPHA_HIGH = 0.8f
private const val SKELETON_PULSE_MS = 900

@Composable
fun StartCardHostWhenSettled(viewModel: ProfileCardViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val paneEntered = LocalPaneEntered.current
    LaunchedEffect(viewModel, lifecycle) {
        // Building the host (first-use Chromium init + page load) blocks the main thread ~1s; a nav entry is RESUMED only once its enter transition ends.
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        snapshotFlow { paneEntered.value }.first { it }
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
internal fun cardSheetShape(): CornerBasedShape =
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
    onChangeDesign: () -> Unit,
    fullWindow: Boolean = false,
    onToggleFullWindow: (() -> Unit)? = null,
) {
    LaunchedEffect(viewModel) { viewModel.onScreenShown() }
    ProvideOpenOverlays {
        val newCard = rememberNewCircleAction { id -> viewModel.onCircleCreated(id, openCard = true) }
        ProfileCardPager(
            viewModel = viewModel,
            onBack = onBack,
            onEdit = onEdit,
            onChangeDesign = onChangeDesign,
            onNewCard = newCard,
            fullWindow = fullWindow,
            onToggleFullWindow = onToggleFullWindow,
        )
    }
}

@Composable
@Suppress("DEPRECATION")
private fun ProfileCardPager(
    viewModel: ProfileCardViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onChangeDesign: () -> Unit,
    onNewCard: () -> Unit,
    fullWindow: Boolean,
    onToggleFullWindow: (() -> Unit)?,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val host by viewModel.host.collectAsStateWithLifecycle()
    val tiles by viewModel.introTiles.collectAsStateWithLifecycle()
    val cover by viewModel.cover.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val fileSystemHandler = getUriHandler()
    // Desktop and web have no share sheet; saving is their way to get the image out.
    val saveInsteadOfShare = remember { isDesktopOrWeb() }
    val nfc = rememberCardNfc()
    val errCard = stringResource(MR.string.profile_card_error)
    val errShare = stringResource(MR.string.profile_card_share_failed)
    val errCircleUnsupported = stringResource(MR.string.profile_card_circle_unsupported)

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
                ProfileCardEvent.CircleCardsUnsupported -> launch { snackbarHostState.showSnackbar(errCircleUnsupported) }
                ProfileCardEvent.DesignSaved, ProfileCardEvent.DesignSaveFailed, ProfileCardEvent.ContentSaveFailed, ProfileCardEvent.ImportUndoExpired, is ProfileCardEvent.AudienceChanged, ProfileCardEvent.ContentRemovedFromCard -> Unit
            }
        }
    }

    // A pane (desktop, web) shows the card in place; a phone's route shows it as a modal sheet.
    val sheeted = onToggleFullWindow == null && !isDesktopOrWeb()
    // Saved, so coming back from the editor finds the sheet open instead of sliding it up again.
    var opened by rememberSaveable { mutableStateOf(false) }
    val sheet = remember { AnchoredDraggableState(if (opened) SheetValue.Open else SheetValue.Dismissed) }
    val sheetHeightPx = LocalWindowInfo.current.containerSize.height * SHEET_HEIGHT_FRACTION
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { scope.launch { viewModel.captureCover() } }
    var leaving by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!leaving) {
            if (sheeted) {
                scope.launch { sheet.animateTo(SheetValue.Dismissed) }
            } else {
                leaving = true
                // Back would otherwise pop past captureCover(), leaving the native card view behind.
                scope.launch {
                    viewModel.captureCover()
                    onBack()
                }
            }
        }
    }
    BackHandler { leave() }
    if (sheeted) {
        LaunchedEffect(sheetHeightPx) {
            sheet.updateAnchors(DraggableAnchors { SheetValue.Open at 0f; SheetValue.Dismissed at sheetHeightPx })
        }
        LaunchedEffect(sheet) {
            snapshotFlow { sheet.anchors.size }.first { it > 0 }
            if (!opened) sheet.animateTo(SheetValue.Open)
            opened = true
            snapshotFlow { sheet.settledValue }.first { it == SheetValue.Dismissed }
            leaving = true
            onBack()
        }
    }

    val cards = uiState.cards
    LaunchedEffect(cards.isNotEmpty()) {
        if (cards.isNotEmpty()) viewModel.onCardOpened(uiState.selectedAudience)
    }
    val selectedIndex = cards.indexOfFirst { it.audience == uiState.selectedAudience }.coerceAtLeast(0)
    val pagerState = rememberPagerState { cards.size }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(cards.isNotEmpty(), selectedIndex) {
        if (cards.isEmpty()) return@LaunchedEffect
        if (!placed) {
            pagerState.scrollToPage(selectedIndex)
            placed = true
        } else if (pagerState.settledPage != selectedIndex && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(selectedIndex)
        }
    }
    val latestCards by rememberUpdatedState(cards)
    LaunchedEffect(pagerState, placed) {
        if (!placed) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }.collect { page ->
            latestCards.getOrNull(page)?.let { viewModel.onCardSelected(it.audience) }
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }.collect { if (it) viewModel.captureCover() }
    }

    var confirmPublicShare by rememberSaveable { mutableStateOf(false) }
    var explainReadOnly by remember { mutableStateOf(false) }

    // Inside a pane the card keeps its card-width sheet; a whole wide window gets the site's wide page, which desktop caps at a reading width.
    val inPane = onToggleFullWindow != null && !fullWindow
    val wide = isExpandedLayout() && (onToggleFullWindow == null || fullWindow)
    // In a pane the card is the pane's whole body, so it takes the pane's corners.
    val sheetShape = if (inPane) MaterialTheme.shapes.extraLarge else cardSheetShape()
    val sheetWidth = when {
        inPane -> Modifier
        !wide -> Modifier.widthIn(max = WIDE_SHEET_MAX_WIDTH)
        isDesktopOrWeb() -> Modifier.widthIn(max = DESKTOP_PAGE_MAX_WIDTH)
        else -> Modifier
    }
    val reservedBottom = FAB_BAND_HEIGHT + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LaunchedEffect(host, reservedBottom) { if (!cardBands) host?.reserveBottom(reservedBottom) }

    CardExpressiveTheme {
        val topColor by cardEdgeColor(uiState.cardTopArgb, uiState.design)
        val bottomColor by cardEdgeColor(uiState.cardBottomArgb, uiState.design)
        // Under a band the sheet's clip rounds the top; the native view rounds only the corners it reaches.
        val cardShape = if (cardBands) sheetShape.copy(topStart = ZeroCorner, topEnd = ZeroCorner) else sheetShape
        val body: @Composable (Modifier) -> Unit = { bodyModifier ->
            Box(modifier = bodyModifier) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind { drawRect(bottomColor) }
                        .padding(top = if (cardBands) TOP_BAND_HEIGHT else 0.dp),
                ) {
                    CardBandsLayout(
                        bottomBand = cardBands,
                        bottomColor = { bottomColor },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        when {
                            uiState.loadFailed && cards.isEmpty() -> CardErrorPlate(
                                message = MR.string.profile_edit_load_failed,
                                onRetry = viewModel::onRetry,
                                modifier = Modifier.align(Alignment.Center),
                            )
                            cards.isEmpty() -> ContainedLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                            else -> HorizontalPager(
                                state = pagerState,
                                key = { cards.getOrNull(it)?.let { card -> audienceKey(card.audience) } ?: it },
                                userScrollEnabled = !uiState.isExporting,
                                modifier = Modifier.fillMaxSize(),
                            ) { page ->
                                val card = cards[page]
                                val selected = card.audience == uiState.selectedAudience
                                val moving = pagerState.isScrollInProgress || (leaving && !sheeted)
                                val shownCover = cover?.image
                                when {
                                    selected && !moving -> CardSurface(
                                        uiState = uiState,
                                        host = host,
                                        backdrop = { bottomColor },
                                        onRetry = viewModel::onRetry,
                                        paintWhileAttached = viewModel::paintWhileAttached,
                                        cover = shownCover,
                                        modifier = Modifier.fillMaxSize(),
                                        shape = cardShape,
                                    )
                                    selected && shownCover != null -> Image(
                                        bitmap = shownCover,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    else -> CardStill(card, tiles[audienceKey(card.audience)], Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                    AboveCard(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .semantics {
                                isTraversalGroup = true
                                traversalIndex = -1f
                            }
                            .navigationBarsPadding()
                            .padding(16.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SnackbarHost(hostState = snackbarHostState) { data ->
                                ReportOverlay()
                                Snackbar(data, Modifier.padding(8.dp))
                            }
                            ShareFab(
                                accent = cards.getOrNull(pagerState.currentPage)?.let { cardAccentArgb(it.design, it.overrides) },
                                isExporting = uiState.isExporting,
                                canShare = uiState.canShare,
                                saveInsteadOfShare = saveInsteadOfShare,
                                onClick = { if (uiState.isCircleSelected) confirmPublicShare = true else viewModel.onShareClicked() },
                            )
                        }
                    }
                }
                // Floats over the page's own top space, as the chrome always did; web's iframe paints over Compose, so it gets a band.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .then(if (cardBands) Modifier.drawBehind { drawRect(topColor) } else Modifier)
                        // The card's webview never hands drags to Compose, so the header alone drags the sheet.
                        .then(if (sheeted) Modifier.anchoredDraggable(sheet, Orientation.Vertical) else Modifier),
                ) {
                    SheetTopChrome(
                        uiState = uiState,
                        onClose = leave.takeIf { !sheeted },
                        onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                        onNewCard = onNewCard.takeIf { uiState.circleCardsSupported },
                        onEdit = onEdit,
                        onChangeDesign = onChangeDesign,
                        onExplainReadOnly = { explainReadOnly = true },
                        pencilContainer = cards.getOrNull(pagerState.currentPage)?.let(::pencilContainer),
                        extraActions = { nfc?.let { CardChromePill { CardNfcAction(it) } } },
                        fullWindow = fullWindow,
                        onToggleFullWindow = onToggleFullWindow,
                    )
                    if (sheeted) BottomSheetDefaults.DragHandle(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
        if (sheeted) {
            Box(modifier = Modifier.fillMaxSize()) {
                Spacer(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val shown = sheet.offset.takeUnless { it.isNaN() }?.let { 1f - it / sheetHeightPx } ?: 0f
                            drawRect(Color.Black.copy(alpha = SCRIM_ALPHA * shown.coerceIn(0f, 1f)))
                        }
                        .clickable(interactionSource = null, indication = null, onClick = leave),
                )
                body(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .then(if (isExpandedLayout()) Modifier else Modifier.widthIn(max = WIDE_SHEET_MAX_WIDTH))
                        .fillMaxWidth()
                        .height(with(LocalDensity.current) { sheetHeightPx.toDp() })
                        .offset { IntOffset(0, sheet.offset.takeUnless { it.isNaN() }?.toInt() ?: sheetHeightPx.toInt()) }
                        .clip(sheetShape),
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
                body(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .then(sheetWidth)
                        .fillMaxSize()
                        .then(if (inPane) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)).padding(top = 8.dp))
                        .clip(sheetShape),
                )
            }
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
        if (explainReadOnly) ReadOnlyCardDialog(onDismiss = { explainReadOnly = false })
    }
}

@Composable
private fun ShareFab(accent: Int?, isExporting: Boolean, canShare: Boolean, saveInsteadOfShare: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (saveInsteadOfShare) MR.string.profile_card_save else MR.string.profile_card_share)
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(accent?.let(::Color) ?: MaterialTheme.colorScheme.primaryContainer, spec)
    val content by animateColorAsState(accent?.let { readableOn(Color(it)) } ?: MaterialTheme.colorScheme.onPrimaryContainer, spec)
    WithTooltip(label) {
        FloatingActionButton(onClick = { if (canShare) onClick() }, containerColor = container, contentColor = content) {
            if (isExporting) {
                LoadingIndicator(modifier = Modifier.size(32.dp))
            } else {
                Icon(
                    imageVector = if (saveInsteadOfShare) Icons.Outlined.Download else Icons.Outlined.Share,
                    contentDescription = label,
                )
            }
        }
    }
}

// A lighter companion to the share FAB: its accent washed 30% over white, or over the card's ground when that is dark.
private fun pencilContainer(card: ProfileCard): Color {
    val ground = Color(card.overrides.palette?.ground?.let(::hexToArgb) ?: CardDesign.baseArgb(card.design))
    return lerp(if (ground.luminance() < 0.5f) ground else Color.White, Color(cardAccentArgb(card.design, card.overrides)), 0.3f)
}

// Black or white, whichever has the higher WCAG contrast against [background].
private fun readableOn(background: Color): Color {
    val l = background.luminance()
    return if ((l + 0.05f) / 0.05f >= 1.05f / (l + 0.05f)) Color.Black else Color.White
}

private enum class SheetValue { Open, Dismissed }

// Web paints the card's frame over any Compose chrome, so only there does the chrome need bands of its own.
private val cardBands = !chromeFloatsOverCard

@Composable
private fun CardBandsLayout(
    bottomBand: Boolean,
    bottomColor: () -> Color,
    modifier: Modifier = Modifier,
    card: @Composable BoxScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { card() }
        if (bottomBand) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind { drawRect(bottomColor()) }
                    .navigationBarsPadding()
                    .height(FAB_BAND_HEIGHT),
            )
        }
    }
}

@Composable
private fun SheetTopChrome(
    uiState: ProfileCardUiState,
    onClose: (() -> Unit)?,
    onSelect: (Int) -> Unit,
    onNewCard: (() -> Unit)?,
    onEdit: () -> Unit,
    onChangeDesign: () -> Unit,
    onExplainReadOnly: () -> Unit,
    pencilContainer: Color?,
    extraActions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    fullWindow: Boolean = false,
    onToggleFullWindow: (() -> Unit)? = null,
) {
    val gap = with(LocalDensity.current) { CHROME_GAP.roundToPx() }
    val edge = with(LocalDensity.current) { 8.dp.roundToPx() }
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .height(TOP_BAND_HEIGHT)
            .semantics {
                isTraversalGroup = true
                traversalIndex = -1f
            },
        content = {
            AboveCard(Modifier) { AudiencePopover(uiState, onSelect, onNewCard) }
            AboveCard(Modifier) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    onToggleFullWindow?.let { toggle ->
                        val label = stringResource(if (fullWindow) MR.string.collapse else MR.string.expand)
                        WithTooltip(label) {
                            ChromeIconButton(onClick = toggle) {
                                Icon(
                                    imageVector = if (fullWindow) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull,
                                    contentDescription = label,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    extraActions()
                    EditDesignMenu(
                        readOnly = uiState.isCircleReadOnly,
                        onExplainReadOnly = onExplainReadOnly,
                        container = pencilContainer,
                        onEdit = onEdit,
                        onChangeDesign = onChangeDesign,
                    )
                    // No sheet handle in a pane, so it keeps a visible way out.
                    onClose?.let { close ->
                        val closeLabel = stringResource(MR.string.close)
                        WithTooltip(closeLabel) {
                            ChromeIconButton(onClick = close) {
                                Icon(Icons.Filled.Close, contentDescription = closeLabel, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val actions = measurables[1].measure(loose)
        val badgeRoom = (width - edge * 2 - actions.width - gap).coerceAtLeast(0)
        val badge = measurables[0].measure(loose.copy(maxWidth = badgeRoom))
        layout(width, height) {
            badge.placeRelative(edge, (height - badge.height) / 2)
            actions.placeRelative(width - edge - actions.width, (height - actions.height) / 2)
        }
    }
}

@Composable
private fun AudiencePopover(uiState: ProfileCardUiState, onSelect: (Int) -> Unit, onNewCard: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    val cards = uiState.cards
    Box {
        AudienceTitle(uiState, onClick = { expanded = true }.takeIf { cards.isNotEmpty() })
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 3.dp,
            modifier = Modifier.widthIn(min = MENU_MIN_WIDTH, max = MENU_MAX_WIDTH).padding(horizontal = MENU_INSET),
        ) {
            ReportOverlay()
            cards.forEachIndexed { index, card ->
                CardMenuItem(
                    card = card,
                    emoji = uiState.circles.emojiOf(card.audience),
                    selected = card.audience == uiState.selectedAudience,
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                )
            }
            onNewCard?.let { addCard ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.profile_card_add_card), style = MaterialTheme.typography.labelLarge) },
                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    onClick = {
                        expanded = false
                        addCard()
                    },
                    modifier = Modifier.clip(MENU_ITEM_SHAPE),
                )
            }
        }
    }
}

@Composable
private fun CardMenuItem(card: ProfileCard, emoji: String?, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val fill by animateColorAsState(if (selected) audienceContainer(card.audience) else Color.Transparent, spec)
    val ink = if (selected) onAudienceContainer(card.audience) else colors.onSurface
    val corner by animateDpAsState(if (selected) MENU_SELECTED_CORNER else MENU_ITEM_CORNER, MaterialTheme.motionScheme.fastSpatialSpec())
    DropdownMenuItem(
        text = {
            Text(
                text = audienceLabel(card.audience),
                style = if (selected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = { AudienceGlyph(audienceIcon(card.audience), emoji, 24.dp) },
        trailingIcon = { CardSwatch(card = card, selected = selected) },
        colors = MenuDefaults.itemColors(textColor = ink, leadingIconColor = ink, trailingIconColor = ink),
        onClick = onClick,
        modifier = Modifier
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(corner))
            .background(fill)
            .semantics { this.selected = selected },
    )
}

@Composable
private fun CardSwatch(card: ProfileCard, selected: Boolean) {
    val palette = card.overrides.palette
    val corner by animateDpAsState(if (selected) MINI_CARD_SELECTED_CORNER else MINI_CARD_CORNER, MaterialTheme.motionScheme.fastSpatialSpec())
    val shape = RoundedCornerShape(corner)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        CardDesignThumbnail(
            design = card.design,
            groundOverride = palette?.ground?.let { Color(hexToArgb(it)) },
            inkOverride = palette?.ink?.let { Color(hexToArgb(it)) },
            modifier = Modifier
                .size(width = MINI_CARD_WIDTH, height = MINI_CARD_WIDTH / CARD_THUMB_ASPECT)
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape),
        )
    }
}

@Composable
private fun EditDesignMenu(readOnly: Boolean, onExplainReadOnly: () -> Unit, container: Color?, onEdit: () -> Unit, onChangeDesign: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(MR.string.edit)
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val fill by animateColorAsState(container ?: MaterialTheme.colorScheme.secondaryContainer, spec)
    val ink by animateColorAsState(container?.let(::readableOn) ?: MaterialTheme.colorScheme.onSecondaryContainer, spec)
    Box {
        WithTooltip(label) {
            FilledTonalIconButton(
                onClick = { if (readOnly) onExplainReadOnly() else open = true },
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = fill, contentColor = ink),
                modifier = Modifier
                    .size(CHROME_CONTROL_SIZE)
                    .shadow(3.dp, CircleShape),
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = label, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
            modifier = Modifier.widthIn(min = 220.dp).padding(horizontal = 8.dp),
        ) {
            ReportOverlay()
            EditMenuItem(Icons.Outlined.Edit, stringResource(MR.string.edit), stringResource(MR.string.profile_card_menu_edit_desc), fill, ink) {
                open = false
                onEdit()
            }
            EditMenuItem(Icons.Outlined.Palette, stringResource(MR.string.profile_card_design_action), stringResource(MR.string.profile_card_menu_design_desc), fill, ink) {
                open = false
                onChangeDesign()
            }
        }
    }
}

@Composable
private fun EditMenuItem(icon: ImageVector, title: String, supporting: String, tint: Color, onTint: Color, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
        text = {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = {
            Box(Modifier.size(40.dp).background(tint, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = onTint, modifier = Modifier.size(20.dp))
            }
        },
        modifier = Modifier.clip(RoundedCornerShape(12.dp)),
    )
}

@Composable
private fun chromePillColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)

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
            modifier = Modifier.size(CHROME_CONTROL_SIZE).semantics { role = Role.Button },
        ) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
    }
}

// A circle card is never what gets shared, so the share action is the same on every card and the swap is said here, where it happens.
@Composable
internal fun SharePublicCardDialog(circleLabel: String, saveInsteadOfShare: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ReportOverlay()
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
internal fun audienceLabel(audience: CardAudience): String = when (audience) {
    CardAudience.Public -> stringResource(MR.string.profile_card_audience_public)
    is CardAudience.Circle -> audience.label.trim().ifEmpty { stringResource(MR.string.profile_card_audience_circle) }
}

internal fun audienceIcon(audience: CardAudience) =
    if (audience is CardAudience.Circle) Icons.Outlined.Groups else Icons.Outlined.Public

@Composable
internal fun AudienceTitle(uiState: ProfileCardUiState, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val named = stringResource(MR.string.profile_card_audience_description, audienceLabel(uiState.selectedAudience))
    val readOnlyLabel = stringResource(MR.string.profile_card_read_only)
    val unsavedLabel = stringResource(MR.string.profile_card_not_saved)
    val description = when {
        uiState.isCircleReadOnly -> "$named $readOnlyLabel"
        uiState.isUnsavedCard -> "$named $unsavedLabel"
        else -> named
    }
    AudienceChip(
        audience = uiState.selectedAudience,
        locked = uiState.isCircleReadOnly,
        unsaved = uiState.isUnsavedCard,
        trailing = onClick?.let { { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.padding(start = 2.dp).size(20.dp)) } },
        modifier = modifier
            .minimumInteractiveComponentSize()
            .then(if (onClick != null) Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClick) else Modifier)
            .clearAndSetSemantics { contentDescription = description },
    )
}

@Composable
internal fun audienceContainer(audience: CardAudience): Color =
    if (audience is CardAudience.Circle) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer

@Composable
internal fun onAudienceContainer(audience: CardAudience): Color =
    if (audience is CardAudience.Circle) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer

@Composable
internal fun AudienceChip(
    audience: CardAudience,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    elevated: Boolean = true,
    maxLines: Int = 1,
    unsaved: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val motion = MaterialTheme.motionScheme
    val container by animateColorAsState(audienceContainer(audience), motion.defaultEffectsSpec())
    val content by animateColorAsState(onAudienceContainer(audience), motion.defaultEffectsSpec())
    val elevation = if (elevated) 2.dp else 0.dp
    Surface(shape = CircleShape, color = container, contentColor = content, shadowElevation = elevation, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .animateContentSize(motion.defaultSpatialSpec())
                .heightIn(min = CHROME_CONTROL_SIZE)
                .padding(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        ) {
            AnimatedContent(
                targetState = audience to unsaved,
                transitionSpec = { fadeIn(motion.defaultEffectsSpec()).togetherWith(fadeOut(motion.fastEffectsSpec())) },
                modifier = Modifier.weight(1f, fill = false),
            ) { (shown, draft) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = audienceIcon(shown), contentDescription = null, modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false)) {
                        Text(
                            text = audienceLabel(shown),
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            maxLines = maxLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (draft) {
                            Text(
                                text = stringResource(MR.string.profile_card_not_saved),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalContentColor.current.copy(alpha = UNSAVED_LABEL_ALPHA),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (locked) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(16.dp))
            }
            trailing?.invoke()
        }
    }
}

@Composable
internal fun ReadOnlyCardDialog(onDismiss: () -> Unit) {
    ReportOverlay()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(stringResource(MR.string.profile_card_read_only_title), textAlign = TextAlign.Center) },
        text = { Text(stringResource(MR.string.profile_card_circle_unsupported)) },
        confirmButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(MR.string.profile_card_read_only_ok)) }
        },
    )
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
    shape: Shape = RectangleShape,
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
    // Desktop and web paint the card's native view over Compose, so it steps aside while a dialog, menu or tooltip is over it.
    val overlays = LocalOpenOverlays.current.takeIf { isDesktopOrWeb() }
    val bounds = remember { mutableStateOf<Rect?>(null) }
    val overlaid by remember(overlays) {
        derivedStateOf { overlays != null && bounds.value?.let(overlays::covers) == true }
    }

    Box(
        modifier = modifier
            .drawBehind { drawRect(ground()) }
            .then(if (overlays != null) Modifier.onGloballyPositioned { bounds.value = it.boundsInWindow() } else Modifier),
    ) {
        if (uiState.loadFailed) {
            CardErrorPlate(
                message = MR.string.profile_edit_load_failed,
                onRetry = onRetry,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        // An unsupported server's /card is its public site, which desktop and web would float over the message.
        if (!uiState.cardUnsupported && attached && !overlaid) {
            host?.let { CardHostView(host = it, modifier = Modifier.fillMaxSize(), layoutWidth = layoutWidth, shape = shape) }
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


internal fun designLabel(design: String): StringResource = when (design) {
    CardDesign.POSTER -> MR.string.profile_card_design_poster
    CardDesign.COLLAGE -> MR.string.profile_card_design_collage
    CardDesign.DOSSIER -> MR.string.profile_card_design_dossier
    CardDesign.LINKS -> MR.string.profile_card_design_links
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

@Composable
internal fun CookieBadge(icon: ImageVector, container: Color, content: Color, size: Dp, iconSize: Dp, emoji: String? = null) {
    Box(
        modifier = Modifier.size(size).background(container, MaterialShapes.Cookie9Sided.toShape()),
        contentAlignment = Alignment.Center,
    ) {
        AudienceGlyph(icon = icon, emoji = emoji, size = iconSize, tint = content)
    }
}

/** A circle's own emoji where it has one, otherwise the audience's icon. */
@Composable
internal fun AudienceGlyph(icon: ImageVector, emoji: String?, size: Dp, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    if (emoji == null) {
        Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
    } else {
        // Emoji font metrics run taller than the icon; the fixed box keeps a row's height the same either way.
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            Text(
                text = emoji.withEmojiFont(),
                fontSize = with(LocalDensity.current) { size.toSp() },
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.wrapContentSize(unbounded = true),
            )
        }
    }
}

// One message for both preview failures, on the neutral ground: it reads as blocked, not alarming; only the icon carries the error role.
@Composable
internal fun CardErrorPlate(message: StringResource, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.padding(32.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            CookieBadge(
                icon = Icons.Outlined.ErrorOutline,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
                size = 64.dp,
                iconSize = 28.dp,
            )
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(onClick = onRetry, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(MR.string.profile_edit_retry))
            }
        }
    }
}
