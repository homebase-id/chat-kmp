package id.homebase.core.ui.screens.moments

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import coil3.compose.AsyncImage
import androidx.window.core.layout.WindowSizeClass
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.auth.initials
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.chat.widget.ExtendPermissionDialog
import id.homebase.core.avatars.AppConnectionStatus
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.OwnerAvatar
import id.homebase.core.moments.MomentsAlbumZoom
import id.homebase.core.moments.MomentsViewMode
import id.homebase.core.moments.services.MomentFeedItem
import id.homebase.core.moments.services.MomentSource
import id.homebase.core.moments.services.MomentsVideoSession
import id.homebase.core.ui.screens.moments.widget.MomentDatePill
import id.homebase.core.ui.screens.moments.widget.MomentInlineVideoTile
import id.homebase.core.ui.screens.moments.widget.MomentMediaGallery
import id.homebase.core.ui.screens.moments.widget.MomentUploadProgressOverlay
import id.homebase.core.ui.screens.moments.widget.SenderAvatarBadge
import id.homebase.core.ui.screens.moments.widget.MaxFeedMediaAspect
import id.homebase.core.ui.screens.moments.widget.aspectRatioFor
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import id.homebase.core.util.isDesktop
import id.homebase.resources.MR
import kotlin.time.Instant
import kotlin.uuid.Uuid
import id.homebase.resources.moments_create_action
import id.homebase.resources.moments_feed_indicator_private
import id.homebase.resources.moments_label
import id.homebase.resources.moments_post_open
import id.homebase.resources.moments_view_album
import id.homebase.resources.moments_view_menu
import id.homebase.resources.moments_view_reels
import id.homebase.resources.moments_view_timeline
import id.homebase.resources.moments_welcome
import id.homebase.resources.upload_failed_action_delete
import id.homebase.resources.upload_failed_action_dismiss
import id.homebase.resources.upload_failed_sheet_body
import id.homebase.resources.upload_failed_sheet_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Page 1 — Main Feed / Timeline.
 *
 * Subscribes to [MomentsFeedViewModel] for the live moment list and renders
 * each post via [MomentMediaGallery] (which delegates to MomentMediaItem for
 * the single-payload case). Empty state shows a placeholder + the FAB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsScreen(
    viewModel: MomentsFeedViewModel,
    extendPermissionViewModel: ExtendPermissionViewModel,
    onCreateMoment: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    /**
     * Open the detail view for a moment. `payloadKey` is the specific media
     * item the user tapped (so the detail carousel can land on that page);
     * `null` for taps that aren't on a specific cell (e.g. the indicator
     * row), which fall back to page 0.
     *
     * Only invoked on compact layouts. On wide desktop the screen renders an
     * embedded detail pane and updates internal selection state instead of
     * navigating.
     */
    onOpenMoment: (momentId: String, payloadKey: String?) -> Unit = { _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Clear the unseen-moments nav badge: while this screen is composed (i.e.
    // the user is on the Moments tab), advance the "last viewed" watermark to
    // the newest moment received from someone else. Re-runs when a newer one
    // arrives mid-view; cancels when the user leaves the tab (composition
    // exits), so it never clears the badge for moments seen on another screen.
    val newestReceivedMs = remember(uiState.moments) {
        uiState.moments.filter { it.senderOdinId != null }.maxOfOrNull { it.createdMs }
    }
    LaunchedEffect(newestReceivedMs) {
        newestReceivedMs?.let { viewModel.markFeedViewed(it) }
    }

    // Permission-drift detection: re-check on every screen entry. The
    // moments-qualified ExtendPermissionViewModel runs an initial check on
    // construction, but its result is cached for the lifetime of the VM.
    // When a new permission is added to the requested set (e.g.
    // DrivePermission.React) for an already-activated user, the cached
    // verdict misses it and the new operation 403s silently. Hooking ON_RESUME
    // forces a fresh check each time the user enters the moments tab so the
    // dialog surfaces the missing grant.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                extendPermissionViewModel.recheckPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Self-renders only when the VM transitions to ShowDialog. No-op otherwise.
    ExtendPermissionDialog(viewModel = extendPermissionViewModel)

    // Desktop wide-screen split: feed on the left, embedded detail pane on the
    // right. Gated on `isDesktop()` so wide phones/tablets stay on the single
    // column — the touch targets and FAB placement on mobile assume one
    // viewport, and the chat module gates its split the same way.
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isWide = isDesktop() &&
        adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
        )

    // Reels is omitted from the desktop view menu; coerce a persisted/synced Reels
    // preference to Timeline on desktop (wide or narrow window) so a pointer user
    // never lands in the touch-first reels view with no menu entry to leave it.
    val effectiveViewMode = if (isDesktop() && uiState.viewMode == MomentsViewMode.Reels) {
        MomentsViewMode.Timeline
    } else {
        uiState.viewMode
    }

    if (isWide) {
        WideMomentsLayout(
            moments = uiState.moments,
            uploadProgress = uiState.uploadProgress,
            pendingLocalPreviews = uiState.pendingLocalPreviews,
            ownerSession = uiState.ownerSession,
            connectionStatus = uiState.connectionStatus,
            driveIsSyncing = uiState.driveIsSyncing,
            hasDriveError = uiState.hasDriveError,
            viewMode = effectiveViewMode,
            onViewModeChange = viewModel::setViewMode,
            albumZoom = uiState.albumZoom,
            onAlbumZoomChange = viewModel::setAlbumZoom,
            onCreateMoment = onCreateMoment,
            onProfileClick = onProfileClick,
            onAddReaction = viewModel::addReaction,
            onDeleteFailedMoment = viewModel::deleteFailedMoment,
            onDismissUpload = viewModel::dismissUpload,
        )
    } else {
        CompactMomentsLayout(
            moments = uiState.moments,
            uploadProgress = uiState.uploadProgress,
            pendingLocalPreviews = uiState.pendingLocalPreviews,
            ownerSession = uiState.ownerSession,
            connectionStatus = uiState.connectionStatus,
            driveIsSyncing = uiState.driveIsSyncing,
            hasDriveError = uiState.hasDriveError,
            viewMode = effectiveViewMode,
            onViewModeChange = viewModel::setViewMode,
            albumZoom = uiState.albumZoom,
            onAlbumZoomChange = viewModel::setAlbumZoom,
            onCreateMoment = onCreateMoment,
            onProfileClick = onProfileClick,
            onOpenMoment = onOpenMoment,
            onAddReaction = viewModel::addReaction,
            onDeleteFailedMoment = viewModel::deleteFailedMoment,
            onDismissUpload = viewModel::dismissUpload,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactMomentsLayout(
    moments: List<MomentFeedItem>,
    uploadProgress: ImmutableMap<Uuid, UploadStatus>,
    pendingLocalPreviews: ImmutableMap<Uuid, Any>,
    ownerSession: OwnerSession?,
    connectionStatus: AppConnectionStatus,
    driveIsSyncing: Boolean,
    hasDriveError: Boolean,
    viewMode: MomentsViewMode,
    onViewModeChange: (MomentsViewMode) -> Unit,
    albumZoom: MomentsAlbumZoom,
    onAlbumZoomChange: (MomentsAlbumZoom) -> Unit,
    onCreateMoment: () -> Unit,
    onProfileClick: () -> Unit,
    onOpenMoment: (momentId: String, payloadKey: String?) -> Unit,
    onAddReaction: (Uuid, String) -> Unit,
    onDeleteFailedMoment: (Uuid) -> Unit,
    onDismissUpload: (Uuid) -> Unit,
) {
    val openLabel = stringResource(MR.string.moments_post_open)
    Scaffold(
        topBar = {
            MomentsTopAppBar(
                ownerSession = ownerSession,
                connectionStatus = connectionStatus,
                driveIsSyncing = driveIsSyncing,
                hasDriveError = hasDriveError,
                viewMode = viewMode,
                onViewModeChange = onViewModeChange,
                onProfileClick = onProfileClick,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateMoment,
                // Lifted off the bottom edge a touch so it doesn't sit on top
                // of whatever's at the bottom of the content under it.
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(MR.string.moments_create_action),
                )
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding)
        if (moments.isEmpty()) {
            EmptyMomentsState(modifier = contentModifier)
        } else when (viewMode) {
            MomentsViewMode.Timeline -> MomentsFeedList(
                moments = moments,
                uploadProgress = uploadProgress,
                pendingLocalPreviews = pendingLocalPreviews,
                selfOdinId = ownerSession?.odinId,
                onOpenMoment = onOpenMoment,
                onAddReaction = onAddReaction,
                openLabel = openLabel,
                selectedMomentId = null,
                // Compact timeline: a tap raises a modal comments + description
                // sheet for the moment (Instagram-style) rather than navigating
                // away to the full-screen detail view.
                commentsSheetOnTap = true,
                onDeleteFailedMoment = onDeleteFailedMoment,
                onDismissUpload = onDismissUpload,
                modifier = contentModifier,
            )
            MomentsViewMode.Album -> MomentsAlbumGrid(
                moments = moments,
                zoom = albumZoom,
                onZoomChange = onAlbumZoomChange,
                onOpenMoment = onOpenMoment,
                pendingLocalPreviews = pendingLocalPreviews,
                modifier = contentModifier,
            )
            MomentsViewMode.Reels -> MomentsReelsView(
                moments = moments,
                modifier = contentModifier,
            )
        }
    }
}

/**
 * Wide-screen Row: feed on the left in a fixed-width column, detail pane on
 * the right filling the rest. The left column gets its own Scaffold (top bar
 * + FAB); the detail pane brings its own Scaffold internally. A
 * VerticalDivider separates the two so the boundary reads cleanly against
 * busy media thumbnails.
 *
 * Selection state is screen-local — the route never changes on wide desktop,
 * so taps just flip `selectedMomentId` and the detail-pane VM (keyed on the
 * id) recomputes from the feed flow. We auto-select the newest moment on
 * first composition so the right pane is never empty when there are moments
 * to show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WideMomentsLayout(
    moments: List<MomentFeedItem>,
    uploadProgress: ImmutableMap<Uuid, UploadStatus>,
    pendingLocalPreviews: ImmutableMap<Uuid, Any>,
    ownerSession: OwnerSession?,
    connectionStatus: AppConnectionStatus,
    driveIsSyncing: Boolean,
    hasDriveError: Boolean,
    viewMode: MomentsViewMode,
    onViewModeChange: (MomentsViewMode) -> Unit,
    albumZoom: MomentsAlbumZoom,
    onAlbumZoomChange: (MomentsAlbumZoom) -> Unit,
    onCreateMoment: () -> Unit,
    onProfileClick: () -> Unit,
    onAddReaction: (Uuid, String) -> Unit,
    onDeleteFailedMoment: (Uuid) -> Unit,
    onDismissUpload: (Uuid) -> Unit,
) {
    val openLabel = stringResource(MR.string.moments_post_open)

    // Reels is an immersive single-column browse — the feed/detail split makes
    // no sense for it, so render it full-area on wide screens too (keeping the
    // top bar so the user can switch back to Timeline/Album). The pager itself
    // Fit-scales media with black letterbox bars, so a very wide viewport just
    // centres the media rather than stretching it.
    if (viewMode == MomentsViewMode.Reels) {
        Scaffold(
            topBar = {
                MomentsTopAppBar(
                    ownerSession = ownerSession,
                    connectionStatus = connectionStatus,
                    driveIsSyncing = driveIsSyncing,
                    hasDriveError = hasDriveError,
                    viewMode = viewMode,
                    onViewModeChange = onViewModeChange,
                    onProfileClick = onProfileClick,
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = onCreateMoment,
                    // Lifted off the bottom edge a touch so it doesn't sit on
                    // top of whatever's at the bottom of the content under it.
                    modifier = Modifier.padding(bottom = 16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(MR.string.moments_create_action),
                    )
                }
            },
        ) { innerPadding ->
            val contentModifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
            if (moments.isEmpty()) {
                EmptyMomentsState(modifier = contentModifier)
            } else {
                MomentsReelsView(moments = moments, modifier = contentModifier)
            }
        }
        return
    }

    var selectedMomentId by remember { mutableStateOf<Uuid?>(null) }

    // Auto-select the newest moment whenever (a) we don't have a selection yet
    // and (b) moments are available. Also re-resolves if the current selection
    // disappears (e.g. it was deleted — fall back to the newest survivor
    // rather than showing an empty pane).
    val newestId by remember(moments) {
        derivedStateOf { moments.firstOrNull()?.id }
    }
    LaunchedEffect(newestId, moments) {
        val current = selectedMomentId
        if (current == null || moments.none { it.id == current }) {
            selectedMomentId = newestId
        }
    }

    // Scale the feed pane with window width — 380dp feels stingy on big
    // external monitors (1920dp+) where it shrinks below 20% of the viewport.
    // Cap at FeedPaneMaxWidth so list rows (small thumbnails + meta) don't
    // grow past the point where extra width is just whitespace per row; the
    // detail pane absorbs any remaining space via weight(1f).
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val feedPaneWidth = (maxWidth * FeedPaneWidthFraction)
            .coerceIn(FeedPaneMinWidth, FeedPaneMaxWidth)
    Row(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .width(feedPaneWidth)
                .fillMaxHeight(),
            topBar = {
                MomentsTopAppBar(
                    ownerSession = ownerSession,
                    connectionStatus = connectionStatus,
                    driveIsSyncing = driveIsSyncing,
                    hasDriveError = hasDriveError,
                    viewMode = viewMode,
                    onViewModeChange = onViewModeChange,
                    onProfileClick = onProfileClick,
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = onCreateMoment,
                    // Lifted off the bottom edge a touch so it doesn't sit on
                    // top of whatever's at the bottom of the content under it.
                    modifier = Modifier.padding(bottom = 16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(MR.string.moments_create_action),
                    )
                }
            },
        ) { innerPadding ->
            val contentModifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
            if (moments.isEmpty()) {
                EmptyMomentsState(modifier = contentModifier)
            } else when (viewMode) {
                MomentsViewMode.Timeline -> MomentsFeedList(
                    moments = moments,
                    uploadProgress = uploadProgress,
                    pendingLocalPreviews = pendingLocalPreviews,
                    selfOdinId = ownerSession?.odinId,
                    onOpenMoment = { id, _ ->
                        selectedMomentId = Uuid.parse(id)
                    },
                    onAddReaction = onAddReaction,
                    openLabel = openLabel,
                    selectedMomentId = selectedMomentId,
                    onDeleteFailedMoment = onDeleteFailedMoment,
                    onDismissUpload = onDismissUpload,
                    modifier = contentModifier,
                )
                MomentsViewMode.Album -> MomentsAlbumGrid(
                    moments = moments,
                    zoom = albumZoom,
                    onZoomChange = onAlbumZoomChange,
                    onOpenMoment = { id, _ ->
                        selectedMomentId = Uuid.parse(id)
                    },
                    pendingLocalPreviews = pendingLocalPreviews,
                    modifier = contentModifier,
                )
                // Unreachable — Reels short-circuits to a full-area layout
                // above before the split pane is built. Kept so the `when`
                // stays exhaustive over MomentsViewMode.
                MomentsViewMode.Reels -> MomentsReelsView(
                    moments = moments,
                    modifier = contentModifier,
                )
            }
        }

        VerticalDivider()

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            val current = selectedMomentId
            if (current != null) {
                // Keying koinViewModel on the moment id gives us a fresh VM
                // every time the user picks a different moment — the existing
                // VM has momentId as an immutable val (it reads
                // commentsService.commentsFor(momentId) once at construction),
                // so swapping ids requires a new instance, not a re-bind.
                val detailVm: MomentDetailViewModel = koinViewModel(
                    key = "moment-detail-pane-$current",
                ) { parametersOf(current, null) }
                MomentDetailPane(
                    viewModel = detailVm,
                    onNavigateBack = null,
                    // Desktop wide split: dock the comments as a persistent
                    // right-hand rail next to the width-capped media instead of
                    // a bottom sheet — uses the horizontal space the big detail
                    // pane otherwise wastes on an oversized image.
                    commentsDocked = true,
                )
            } else if (moments.isEmpty()) {
                EmptyMomentsState(modifier = Modifier.fillMaxSize())
            }
        }
    }
    }
}

private const val FeedPaneWidthFraction = 0.28f
private val FeedPaneMinWidth = 380.dp
private val FeedPaneMaxWidth = 480.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentsFeedList(
    moments: List<MomentFeedItem>,
    uploadProgress: ImmutableMap<Uuid, UploadStatus> = persistentMapOf(),
    pendingLocalPreviews: ImmutableMap<Uuid, Any> = persistentMapOf(),
    selfOdinId: OdinId?,
    onOpenMoment: (momentId: String, payloadKey: String?) -> Unit,
    onAddReaction: (Uuid, String) -> Unit,
    openLabel: String,
    selectedMomentId: Uuid?,
    // When true (compact timeline), a single tap opens a modal comments +
    // description bottom sheet for that moment (Instagram-style) instead of
    // invoking [onOpenMoment]. The wide-desktop layout leaves this false so a
    // tap still selects the moment for the embedded detail pane.
    commentsSheetOnTap: Boolean = false,
    onDeleteFailedMoment: (Uuid) -> Unit = {},
    onDismissUpload: (Uuid) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var playingMomentId by remember { mutableStateOf<Uuid?>(null) }
    // Which moment (if any) has its comments bottom sheet open. Null = closed.
    // Only set on the compact timeline (see [commentsSheetOnTap]); the modal
    // sheet anchors to this id and blocks the feed until dismissed.
    var commentsMomentId by remember { mutableStateOf<Uuid?>(null) }
    // Payload key of the media that was visible when the comments sheet was
    // opened, so the media band above the sheet shows that exact item (the
    // selected carousel page), not always page 0.
    var commentsPayloadKey by remember { mutableStateOf<String?>(null) }
    // Lifted out of the comments sheet host so the media band can track the
    // sheet's *target* state. The sheet's onDismissRequest only fires once the
    // slide-down completes; keying the band off `commentsMomentId` alone makes
    // the shrunk band linger over the feed for the whole slide before snapping
    // away. Hiding it the moment the swipe-down settles toward Hidden restores
    // the full-size feed in parallel with the slide instead.
    val commentsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Which moment (if any) has its "who reacted" roster open. Null = closed.
    // Opened by long-pressing a reaction toggle on a feed card.
    var reactionsMomentId by remember { mutableStateOf<Uuid?>(null) }
    // Session-scoped mute state: a Koin singleton so it survives navigating
    // away from the moments tab and back. Default muted on every fresh app
    // launch (Instagram-style); once the user unmutes, every subsequent
    // autoplay in this session follows.
    val videoSession = koinInject<MomentsVideoSession>()
    val isMuted by videoSession.isMuted.collectAsStateWithLifecycle()

    // Precompute the set of moments that have *any* video payload — we only
    // ever autoplay cards backed by a video. Recomputes when the feed list
    // changes (new posts, deletions); a single membership-test per scroll
    // frame is fine.
    val videoMomentIds = remember(moments) {
        moments.asSequence()
            .filter { m ->
                m.payloads.any { p ->
                    val ct = p.contentType ?: ""
                    ct.startsWith("video/") || ct == "application/vnd.apple.mpegurl"
                }
            }
            .map { it.id.toString() }
            .toSet()
    }

    // Autoplay driver: the most-centred *video* moment in the viewport
    // becomes the playing one. snapshotFlow already dedups via structural
    // equality, so no extra distinctUntilChanged — emissions only happen
    // when the active key actually changes (see CLAUDE.md note on the
    // double-equality-check trap).
    //
    // Disabled on desktop: on a pointer-driven window the feed shouldn't start
    // playing videos on its own as the user scrolls — playback stays manual
    // (tap a card's play button, which sets playingMomentId via onToggleVideoPlay).
    val feedAutoplayEnabled = !isDesktop()
    LaunchedEffect(listState, videoMomentIds, feedAutoplayEnabled) {
        if (!feedAutoplayEnabled) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.isEmpty() || videoMomentIds.isEmpty()) return@snapshotFlow null
            val viewportCenter =
                (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo
                .filter { (it.key as? String) in videoMomentIds }
                .minByOrNull {
                    kotlin.math.abs((it.offset + it.size / 2) - viewportCenter)
                }
                ?.key as? String
        }.collect { activeKey ->
            playingMomentId = activeKey?.let {
                runCatching { Uuid.parse(it) }.getOrNull()
            }
        }
    }

    Box(modifier = modifier) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        // Chat-style: feed is sorted newest-first by the service, and
        // reverseLayout pins the newest item to the bottom of the
        // viewport. Initial scroll lands on the newest moment; older
        // moments are reached by scrolling up.
        reverseLayout = true,
    ) {
        items(moments, key = { it.id.toString() }) { moment ->
            val isActive = playingMomentId == moment.id
            MomentPostCard(
                moment = moment,
                uploadStatus = uploadProgress[moment.id],
                pendingLocalPreviewModel = pendingLocalPreviews[moment.id],
                selfOdinId = selfOdinId,
                isSelected = !commentsSheetOnTap &&
                    selectedMomentId != null && moment.id == selectedMomentId,
                onCardClick = { payloadKey -> onOpenMoment(moment.id.toString(), payloadKey) },
                onAddReaction = { emoji -> onAddReaction(moment.id, emoji) },
                commentsSheetOnTap = commentsSheetOnTap,
                onOpenComments = { payloadKey ->
                    commentsPayloadKey = payloadKey
                    commentsMomentId = moment.id
                },
                onShowReactors = { reactionsMomentId = moment.id },
                onClickLabel = openLabel,
                onDeleteFailedMoment = { onDeleteFailedMoment(moment.id) },
                onDismissUpload = { onDismissUpload(moment.id) },
                // playingMomentId is now visibility-driven (see snapshotFlow
                // above), so a single-video card autoplays as soon as it
                // becomes the most-centred video moment in the viewport.
                // The onToggleVideoPlay tap path stays as a manual override
                // for the rare "user explicitly wanted to stop or restart"
                // case — taps still toggle, autoplay re-engages on the next
                // visibility update.
                // Pause the feed's own players while the comments band is up
                // (the band owns playback) so we never mount two surfaces for
                // the same moment.
                isVideoPlaying = isActive && commentsMomentId == null,
                onToggleVideoPlay = {
                    playingMomentId = if (isActive) null else moment.id
                },
                // autoplayActive feeds the carousel branch: when the card is
                // the active one, the carousel auto-plays whichever video
                // page is currently visible (and pauses when the user swipes
                // to a non-video page or away from this card).
                autoplayActive = isActive,
                isMuted = isMuted,
                onToggleMute = videoSession::toggleMuted,
                commentsOpen = commentsSheetOnTap && commentsMomentId == moment.id,
            )
        }
    }

        // While a card's comments sheet is open, cover the feed with the tapped
        // moment's media shrunk to a band at the top (whole frame, fit) so it
        // sits fully above the (transparent-scrim) sheet — mirroring the detail
        // screen. Deterministic: no list scrolling, and the feed's own players
        // are paused below (isVideoPlaying gate) so only the band plays.
        val bandMomentId = commentsMomentId
        if (commentsSheetOnTap && bandMomentId != null) {
            val bandMoment = moments.firstOrNull { it.id == bandMomentId }
            if (bandMoment != null) {
                // Crossfade the band with the sheet's slide rather than hard-
                // cutting to the live feed. `onDismissRequest` only fires after
                // the slide completes, so leaving the opaque band up for the
                // whole slide felt laggy; hard-removing it the instant the swipe
                // settles toward Hidden made the full-size feed card snap in
                // behind the still-moving sheet (jarring). Fading the band out
                // as the sheet settles reveals the feed gradually, in parallel
                // with the slide. Stays composed (gated on `commentsMomentId`)
                // until `onDismiss` clears it, so the fade always completes.
                val bandAlpha by animateFloatAsState(
                    targetValue = if (commentsSheetState.targetValue == SheetValue.Hidden) 0f else 1f,
                    label = "momentCommentsBandFade",
                )
                MomentCommentsMediaBand(
                    moment = bandMoment,
                    initialPayloadKey = commentsPayloadKey,
                    isMuted = isMuted,
                    onToggleMute = videoSession::toggleMuted,
                    modifier = Modifier.graphicsLayer { alpha = bandAlpha },
                )
            }
        }
    }

    // Instagram-style: a tap raises a modal comments + description sheet
    // anchored to the tapped moment. ModalBottomSheet brings its own scrim and
    // blocks the feed until the user swipes it down (or taps the scrim), so the
    // thread stays locked to the moment it was opened from.
    val sheetMomentId = commentsMomentId
    if (commentsSheetOnTap && sheetMomentId != null) {
        MomentCommentsSheetHost(
            momentId = sheetMomentId,
            sheetState = commentsSheetState,
            onDismiss = { commentsMomentId = null },
        )
    }

    // "Who reacted" roster, raised by long-pressing a reaction toggle. Spins up
    // a throwaway per-moment detail VM (same pattern as the comments host) so
    // the server-loaded reactor list is fetched and torn down with the sheet.
    val reactorsMomentId = reactionsMomentId
    if (reactorsMomentId != null) {
        MomentReactionsSheetHost(
            momentId = reactorsMomentId,
            onDismiss = { reactionsMomentId = null },
        )
    }
}

/**
 * Hosts the timeline's modal comments sheet for a moment: spins up a
 * per-moment [MomentDetailViewModel] (keyed by id, same as the wide-desktop
 * detail pane) inside a disposable [ViewModelStore] so the comments
 * subscription is torn down as soon as the sheet is dismissed — no leaked VMs
 * as the user opens one moment's thread after another.
 *
 * Reuses the detail screen's [MomentCommentsSheet] so the timeline and the
 * full-screen detail view show an identical thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentCommentsSheetHost(
    momentId: Uuid,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    DisposableViewModelStoreOwner {
        val detailVm: MomentDetailViewModel = koinViewModel(
            key = "moment-comments-sheet-$momentId",
        ) { parametersOf(momentId, null) }
        val uiState by detailVm.uiState.collectAsStateWithLifecycle()
        // Deleting the moment from the sheet's description menu does an
        // optimistic local remove, so the feed card vanishes on its own. There's
        // no nav target to pop here (unlike the reels view), so just close the
        // sheet so it doesn't linger over the now-deleted moment.
        LaunchedEffect(detailVm) {
            detailVm.events.collect { event ->
                if (event is MomentDetailUiEvent.MomentDeleted) onDismiss()
            }
        }
        MomentCommentsSheet(
            uiState = uiState,
            onAction = detailVm::onAction,
            onDismiss = onDismiss,
            sheetState = sheetState,
        )
    }
}

/**
 * Fraction of the screen the tapped moment's media occupies (pinned to the top)
 * while its comments sheet is open. The sheet takes the bottom ~0.65, so the two
 * leave a small black gap and both stay visible — see [MomentCommentsMediaBand].
 */
private const val MOMENT_FEED_MEDIA_FRACTION_WITH_COMMENTS = 1f / 3f

/**
 * Opaque overlay drawn over the feed while a moment's comments sheet is open: the
 * media the user was looking at, shrunk to the top
 * [MOMENT_FEED_MEDIA_FRACTION_WITH_COMMENTS] of the screen and rendered
 * fit-to-content (whole frame), so it sits fully above the transparent-scrim
 * sheet. Mirrors the detail screen's shrink without touching the (reverse-layout)
 * list scroll position. The feed's own video players are paused while this is up
 * (see the `isVideoPlaying` gate at the card call site), so only this band plays.
 *
 * Shows the single [initialPayloadKey] item — the carousel page that was visible
 * when comments were opened — rather than the whole gallery, so a carousel stays
 * on the selected item (instead of snapping back to page 0) and an image gets the
 * same fit-to-content treatment as a video.
 */
@Composable
private fun MomentCommentsMediaBand(
    moment: MomentFeedItem,
    initialPayloadKey: String?,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val targetPayload = remember(moment.payloads, initialPayloadKey) {
        moment.payloads.firstOrNull { it.key == initialPayloadKey }
            ?: moment.payloads.firstOrNull()
    }
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (targetPayload != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(MOMENT_FEED_MEDIA_FRACTION_WITH_COMMENTS)
                    .align(Alignment.TopCenter),
            ) {
                val isVideo = targetPayload.contentType?.startsWith("video/") == true ||
                    targetPayload.contentType == "application/vnd.apple.mpegurl"
                if (isVideo) {
                    var playing by remember(moment.id, targetPayload.key) {
                        mutableStateOf(true)
                    }
                    MomentInlineVideoTile(
                        payload = targetPayload,
                        fileId = moment.fileId,
                        driveId = moment.driveId,
                        keyHeader = moment.keyHeader,
                        previewThumbnail = moment.previewThumbnail,
                        isUploading = false,
                        isPlaying = playing,
                        onPlayTap = { playing = !playing },
                        onDoubleTap = {},
                        isMuted = isMuted,
                        onToggleMute = onToggleMute,
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null,
                        modifier = Modifier.fillMaxSize(),
                        fitToContent = true,
                    )
                } else {
                    // Reuse the gallery's single-payload (fit-to-content) layout
                    // so the whole image is visible, just like the video branch.
                    MomentMediaGallery(
                        payloads = listOf(targetPayload),
                        fileId = moment.fileId,
                        driveId = moment.driveId,
                        previewThumbnail = moment.previewThumbnail,
                        keyHeader = moment.keyHeader,
                        messageId = moment.id,
                        downloadingFiles = emptySet(),
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null,
                        isUploading = false,
                        isMuted = isMuted,
                        onToggleMute = onToggleMute,
                        autoplayActive = true,
                        modifier = Modifier.fillMaxSize(),
                        fitToContent = true,
                    )
                }
            }
        }
    }
}

/**
 * Hosts the timeline's "who reacted" roster for a moment, opened by long-pressing
 * a reaction toggle. Mirrors [MomentCommentsSheetHost]: a per-moment
 * [MomentDetailViewModel] in a disposable [ViewModelStore], dispatched to
 * [MomentDetailUiAction.OpenReactionsSheet] on open so the reactor list is
 * fetched once and torn down with the sheet. Reuses the detail screen's
 * [ReactionsBottomSheet] so the two surfaces show an identical roster.
 */
@Composable
private fun MomentReactionsSheetHost(
    momentId: Uuid,
    onDismiss: () -> Unit,
) {
    DisposableViewModelStoreOwner {
        val detailVm: MomentDetailViewModel = koinViewModel(
            key = "moment-reactions-sheet-$momentId",
        ) { parametersOf(momentId, null) }
        val uiState by detailVm.uiState.collectAsStateWithLifecycle()
        LaunchedEffect(momentId) {
            detailVm.onAction(MomentDetailUiAction.OpenReactionsSheet)
        }
        if (uiState.showReactionsSheet) {
            ReactionsBottomSheet(
                isLoading = uiState.isReactionsLoading,
                reactions = uiState.reactions,
                onDismiss = {
                    detailVm.onAction(MomentDetailUiAction.DismissReactionsSheet)
                    onDismiss()
                },
            )
        }
    }
}

/**
 * Scopes its [content] to a fresh [ViewModelStore] that is cleared when the
 * content leaves composition. Lets a [koinViewModel] hosted outside the
 * navigation back stack (here, the timeline's comments sheet) be disposed when
 * it closes instead of living for the whole screen (the default
 * `LocalViewModelStoreOwner` is the screen/back-stack entry, which never
 * clears mid-screen).
 */
@Composable
private fun DisposableViewModelStoreOwner(
    content: @Composable () -> Unit,
) {
    val storeOwner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(storeOwner) {
        onDispose { storeOwner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentPostCard(
    moment: MomentFeedItem,
    uploadStatus: UploadStatus?,
    pendingLocalPreviewModel: Any?,
    selfOdinId: OdinId?,
    isSelected: Boolean,
    // Carries the payload key of whichever carousel page was visible at tap
    // time so the detail screen can open at the same page (and resume the
    // playing video where the feed left off). `null` for description-only
    // moments and falls through to "open at page 0" downstream.
    onCardClick: (payloadKey: String?) -> Unit,
    onAddReaction: (emoji: String) -> Unit,
    // Compact timeline only: when true a single tap opens the modal comments
    // sheet (via [onOpenComments]) instead of calling [onCardClick].
    commentsSheetOnTap: Boolean,
    // Carries the payload key of whichever carousel page was visible at tap
    // time so the comments band can open on the media the user was actually
    // looking at (not always page 0). `null` for single-payload moments.
    onOpenComments: (payloadKey: String?) -> Unit,
    // Long-press on a reaction toggle opens the server-loaded "who reacted"
    // roster for this moment — same sheet the detail screen uses.
    onShowReactors: () -> Unit,
    onClickLabel: String,
    onDeleteFailedMoment: () -> Unit,
    onDismissUpload: () -> Unit,
    isVideoPlaying: Boolean = false,
    onToggleVideoPlay: () -> Unit = {},
    autoplayActive: Boolean = false,
    isMuted: Boolean = true,
    onToggleMute: () -> Unit = {},
    // True while this card's comments sheet is open. Switches the media to
    // fit-with-letterbox so the whole photo/video is visible (paired with the
    // shrink/scroll that brings the card above the sheet).
    commentsOpen: Boolean = false,
) {
    // Local sheet state — only one moment's failed-upload sheet can be open
    // at a time per card, and the sheet's lifetime tracks the card. No need
    // to lift this to the VM (it's pure presentation, no cross-screen
    // implications, no need to survive process death).
    var failedSheetOpen by remember { mutableStateOf(false) }
    val failedSheetState = rememberModalBottomSheetState()

    // Floating-emoji feedback for double/triple-tap reactions. The same
    // controller backs the detail screen's reaction buttons, so adding a
    // heart in the feed and removing it on the detail screen produce
    // visually identical animations.
    val floatingController = rememberFloatingReactionController()
    val scope = rememberCoroutineScope()

    // Multi-tap state lives at the composable level (not inside
    // pointerInput's coroutine) so the dispatch coroutine is hosted by the
    // composable's lifecycle and is not coupled to detector restarts.
    var tapCount by remember { mutableStateOf(0) }
    var dispatchJob by remember { mutableStateOf<Job?>(null) }

    // Tracks which carousel page the user is currently looking at so a
    // tap-to-detail opens on the same page. Defaults to the first payload
    // (correct for single-payload moments where the carousel isn't shown);
    // updated by MomentMediaGallery's onVisiblePayloadChanged callback when
    // the moment has multiple payloads.
    var visiblePayloadKey by remember(moment.id) {
        mutableStateOf(moment.payloads.firstOrNull()?.key)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Single multi-tap detector handles all taps:
            // 1 tap → open detail (dispatch is delayed ≈[MultiTapTimeoutMs]
            //   so we can disambiguate from double/triple),
            // 2 taps → heart reaction + floating-emoji feedback,
            // 3 taps → flame reaction + floating-emoji feedback.
            // Works on the media area because [MomentMediaGallery] is now
            // told `onMediaClick = null`, which keeps the inner per-cell
            // pointer detectors uninstalled and lets taps reach this Box.
            .pointerInput(moment.id) {
                detectTapGestures(
                    onTap = {
                        tapCount += 1
                        dispatchJob?.cancel()
                        dispatchJob = scope.launch {
                            delay(MultiTapTimeoutMs)
                            val resolved = tapCount
                            tapCount = 0
                            when (resolved) {
                                // Compact timeline opens the modal comments
                                // sheet for this moment; wide-desktop still
                                // selects the moment for the embedded detail
                                // pane.
                                1 -> if (commentsSheetOnTap) {
                                    onOpenComments(visiblePayloadKey)
                                } else {
                                    onCardClick(visiblePayloadKey)
                                }
                                2 -> {
                                    val isRemoving = HeartEmoji in moment.ownReactions
                                    floatingController.show(HeartEmoji, isRemoving)
                                    onAddReaction(HeartEmoji)
                                }
                                else -> if (resolved >= 3) {
                                    val isRemoving = FlameEmoji in moment.ownReactions
                                    floatingController.show(FlameEmoji, isRemoving)
                                    onAddReaction(FlameEmoji)
                                }
                            }
                        }
                    },
                )
            }
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.large)
            // Selected ring shows up in wide-screen mode where the right pane
            // mirrors this card's contents. In compact mode `isSelected` is
            // always false, so the background stays transparent.
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
            )
            // Talkback / accessibility services need a click action because
            // the raw pointerInput above isn't visible to them.
            .semantics {
                onClick(label = onClickLabel) {
                    if (commentsSheetOnTap) {
                        onOpenComments(visiblePayloadKey)
                    } else {
                        onCardClick(visiblePayloadKey)
                    }
                    true
                }
            },
    ) {
        if (moment.payloads.isEmpty()) {
            if (pendingLocalPreviewModel != null) {
                // Placeholder window after postMomentAsync: the optimistic row
                // exists but thumbnail generation / encryption are still
                // running on the post sender's background scope. Render the
                // local preview model (photo file path, or the extracted poster
                // JPEG bytes for a video — a raw video path can't be decoded by
                // the image loader, so it would otherwise render black) so the
                // tile shows the picked media immediately, with the upload
                // overlay (Preparing → Sending → …) on top. Once writeUpdate
                // lands real payload descriptors, this branch stops firing and
                // the normal MomentMediaGallery path takes over.
                Box {
                    AsyncImage(
                        model = pendingLocalPreviewModel,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Crop,
                    )
                    // Always surface a spinner while we're in the placeholder
                    // window: being here means payloads haven't landed yet, so
                    // the moment is still preparing even in the brief beat
                    // before the first PayloadBundlingEvent.Preparing arrives
                    // (which is when `uploadStatus` first becomes non-null).
                    MomentUploadProgressOverlay(
                        status = uploadStatus ?: UploadStatus.Preparing,
                        modifier = Modifier.matchParentSize(),
                        onPermanentFailureTap = { failedSheetOpen = true },
                    )
                }
            } else {
                // Description-only / corrupt-payload moment — still render a tile so
                // the user sees something tappable.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(24.dp),
                ) {
                    Text(
                        text = moment.description.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            // Wrap the gallery so the upload-progress overlay can size to the
            // media only (matchParentSize) — date pill / sender avatar / info
            // chips below stay readable through the scrim because they sit
            // outside this Box on the outer card.
            Box {
                val singleVideoPayload = moment.payloads.singleOrNull()?.takeIf { p ->
                    p.contentType?.startsWith("video/") == true ||
                        p.contentType == "application/vnd.apple.mpegurl"
                }
                if (singleVideoPayload != null) {
                    // Tap-to-play tile. Inner detectTapGestures consumes single
                    // and double taps on the video area — heart-by-double-tap
                    // is preserved by forwarding to onAddReaction; the
                    // triple-tap flame is lost only on the video tile itself
                    // (still works on header / badges / engagement strip).
                    // Same landscape cap as the photo path so a wide video
                    // doesn't render as a thin strip (see [MaxFeedMediaAspect]).
                    val aspect = (aspectRatioFor(singleVideoPayload) ?: 1f)
                        .coerceAtMost(MaxFeedMediaAspect)
                    MomentInlineVideoTile(
                        payload = singleVideoPayload,
                        fileId = moment.fileId,
                        driveId = moment.driveId,
                        keyHeader = moment.keyHeader,
                        previewThumbnail = moment.previewThumbnail,
                        isUploading = uploadStatus != null,
                        isPlaying = isVideoPlaying,
                        onPlayTap = onToggleVideoPlay,
                        onDoubleTap = { onAddReaction(HeartEmoji) },
                        isMuted = isMuted,
                        onToggleMute = onToggleMute,
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect),
                        fitToContent = commentsOpen,
                    )
                } else {
                    MomentMediaGallery(
                        payloads = moment.payloads,
                        fileId = moment.fileId,
                        driveId = moment.driveId,
                        previewThumbnail = moment.previewThumbnail,
                        keyHeader = moment.keyHeader,
                        messageId = moment.id,
                        downloadingFiles = emptySet(),
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null,
                        // Photos render through the inline pinch-zoom wrapper,
                        // whose gesture detector consumes taps over the image —
                        // so the card-level multi-tap detector never sees them
                        // (#874). Route a single tap (open) through the per-cell
                        // handler so the zoom wrapper's onTap opens the moment,
                        // exactly like the card detector's single-tap does;
                        // pinch + double-tap-zoom still work, and on the image
                        // they replace double/triple-tap-react (taps elsewhere on
                        // the card still react). Video tiles keep their own play
                        // tap and double-tap-heart via the onDoubleTap callback.
                        onMediaClick = { tapped ->
                            if (commentsSheetOnTap) onOpenComments(tapped.key)
                            else onCardClick(tapped.key)
                        },
                        isUploading = uploadStatus != null,
                        isMuted = isMuted,
                        onToggleMute = onToggleMute,
                        onDoubleTap = { onAddReaction(HeartEmoji) },
                        autoplayActive = autoplayActive,
                        onVisiblePayloadChanged = { visiblePayloadKey = it },
                        fitToContent = commentsOpen,
                    )
                }

                if (uploadStatus != null) {
                    MomentUploadProgressOverlay(
                        status = uploadStatus,
                        modifier = Modifier.matchParentSize(),
                        onPermanentFailureTap = { failedSheetOpen = true },
                    )
                }
            }
        }

        // Bottom-left: localized capture date pill, matching the reel detail's
        // bottom-left date. Sourced from the moment's userDate (which is set
        // from EXIF if any photo had it, else the post-time clock — see
        // MomentComposeViewModel.deriveMomentInstant). The video duration chip
        // moved to bottom-right (see MomentInlineVideoTile) so this corner is
        // clear.
        MomentDatePill(
            timestamp = Instant.fromEpochMilliseconds(moment.userDateMs),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        )

        // Top-left: author avatar — shown on every moment, including the user's
        // own. Prefer originalAuthor: it resolves to the post's author on both
        // inbound transfers and the user's own drive copy, where senderOdinId
        // comes back null after sync (the server strips it; only the optimistic
        // local copy stamps self). Fall back to senderOdinId, then to the active
        // user (a null author only happens on legacy posts, which are the user's
        // own), so the badge always renders with the correct face.
        val avatarOdinId = moment.originalAuthor ?: moment.senderOdinId ?: selfOdinId
        if (avatarOdinId != null) {
            SenderAvatarBadge(
                odinId = avatarOdinId,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            )
        }

        // Center-left: reaction toggles — heart + flame, the same interactive
        // column the detail screen renders (reusing [EmojiReactionButton] so the
        // two can't drift), minus the comment icon. Tapping toggles the
        // reaction with the same floating-emoji confirmation as a
        // double/triple-tap; long-press opens the "who reacted" roster. Skipped
        // on description-only moments (nothing to overlay against), mirroring the
        // detail screen.
        if (moment.payloads.isNotEmpty()) {
            val heartCount = remember(moment.reactionPreview) {
                countReactionsByEmoji(moment.reactionPreview, HeartEmoji)
            }
            val flameCount = remember(moment.reactionPreview) {
                countReactionsByEmoji(moment.reactionPreview, FlameEmoji)
            }
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmojiReactionButton(
                    emoji = HeartEmoji,
                    count = heartCount,
                    isActive = HeartEmoji in moment.ownReactions,
                    onClick = {
                        val isRemoving = HeartEmoji in moment.ownReactions
                        floatingController.show(HeartEmoji, isRemoving)
                        onAddReaction(HeartEmoji)
                    },
                    onLongPress = onShowReactors,
                )
                EmojiReactionButton(
                    emoji = FlameEmoji,
                    count = flameCount,
                    isActive = FlameEmoji in moment.ownReactions,
                    onClick = {
                        val isRemoving = FlameEmoji in moment.ownReactions
                        floatingController.show(FlameEmoji, isRemoving)
                        onAddReaction(FlameEmoji)
                    },
                    onLongPress = onShowReactors,
                )
            }
        }

        // Floating-emoji overlay: pops + fades in when a double/triple-tap
        // lands a reaction, then fades back out via [floatingEmojiHideJob].
        // Sits centered on the card so the user sees clear confirmation that
        // their multi-tap registered. Drawn after all other overlays so it
        // lands on top of media + badges.
        FloatingReactionOverlay(
            display = floatingController.display,
            modifier = Modifier.align(Alignment.Center),
        )

        // Action sheet for a permanently-failed upload. Only the
        // permanent-Failed overlay surfaces the tap that flips
        // `failedSheetOpen` to true, so this never opens for in-progress or
        // transient-retry states. Delete removes the local optimistic write
        // (the upload never reached the server, so nothing remote to
        // delete); Dismiss just clears the overlay and leaves the local
        // copy in the feed.
        if (failedSheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { failedSheetOpen = false },
                sheetState = failedSheetState,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(MR.string.upload_failed_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(MR.string.upload_failed_sheet_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            failedSheetOpen = false
                            onDeleteFailedMoment()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(MR.string.upload_failed_action_delete))
                    }
                    TextButton(
                        onClick = {
                            failedSheetOpen = false
                            onDismissUpload()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(MR.string.upload_failed_action_dismiss))
                    }
                    Spacer(modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
    }
}

/**
 * True when this is one of the active user's own moments and no recipients
 * were recorded — either the audience picker selected nobody, or the post
 * pre-dates the source field and the flat recipients list is empty.
 *
 * `senderOdinId` is null on the server-side copy of the sender's own file
 * but the optimistic writer stamps it with the active user's domain on the
 * local copy — same convention as the detail VM's "isMine" check. We accept
 * either null or a match on [self] for "this is mine."
 */
private fun MomentFeedItem.isPrivate(self: OdinId?): Boolean {
    val isMine = senderOdinId == null || (self != null && senderOdinId == self)
    if (!isMine) return false
    val src = source
    return when (src) {
        null -> recipients.isEmpty()
        is MomentSource.Conversation -> false
        is MomentSource.Audience ->
            src.groupIds.isEmpty() && src.individuals.isEmpty() && recipients.isEmpty()
    }
}

@Composable
private fun EmptyMomentsState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(96.dp),
            )
            Text(
                text = stringResource(MR.string.moments_welcome),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

/**
 * Header for the Moments feed. Mirrors the visual language of
 * `ConversationListPane`: owner avatar (with connection/sync status indicator)
 * on the left, then a bold "Moments" title that auto-sizes the same way the
 * chat title does. No actions on the right — search and overflow are
 * chat-specific.
 *
 * The avatar fades in once an owner session has loaded; the title is always
 * present so the header is never empty during cold start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentsTopAppBar(
    ownerSession: OwnerSession?,
    connectionStatus: AppConnectionStatus,
    driveIsSyncing: Boolean,
    hasDriveError: Boolean,
    viewMode: MomentsViewMode,
    onViewModeChange: (MomentsViewMode) -> Unit,
    onProfileClick: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Spacer(modifier = Modifier.width(20.dp))
                AnimatedVisibility(
                    visible = ownerSession != null,
                    enter = fadeIn(animationSpec = tween(300, delayMillis = 200)),
                    exit = fadeOut(animationSpec = tween(150)),
                ) {
                    ownerSession?.let { session ->
                        OwnerAvatar(
                            odinId = session.odinId,
                            profileImageData = null,
                            initials = session.initials(),
                            connectionStatus = connectionStatus,
                            driveIsSyncing = driveIsSyncing,
                            hasDriveError = hasDriveError,
                            options = AvatarOptions(
                                size = 32.dp,
                                fontSize = 12.sp,
                                onClick = onProfileClick,
                            ),
                            animatedVisibilityScope = this@AnimatedVisibility,
                            sharedTransitionScope = null,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(MR.string.moments_label),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 14.sp,
                        maxFontSize = 22.sp,
                    ),
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
        },
        actions = {
            MomentsViewModeMenu(
                selected = viewMode,
                onSelect = onViewModeChange,
            )
        },
    )
}

@Composable
private fun MomentsViewModeMenu(
    selected: MomentsViewMode,
    onSelect: (MomentsViewMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val menuLabel = stringResource(MR.string.moments_view_menu)
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = when (selected) {
                    MomentsViewMode.Timeline -> Icons.Outlined.ViewAgenda
                    MomentsViewMode.Album -> Icons.Outlined.PhotoAlbum
                    MomentsViewMode.Reels -> Icons.Outlined.Slideshow
                },
                contentDescription = menuLabel,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MomentsViewModeMenuItem(
                label = stringResource(MR.string.moments_view_timeline),
                icon = Icons.Outlined.ViewAgenda,
                isSelected = selected == MomentsViewMode.Timeline,
                onClick = {
                    expanded = false
                    onSelect(MomentsViewMode.Timeline)
                },
            )
            MomentsViewModeMenuItem(
                label = stringResource(MR.string.moments_view_album),
                icon = Icons.Outlined.PhotoAlbum,
                isSelected = selected == MomentsViewMode.Album,
                onClick = {
                    expanded = false
                    onSelect(MomentsViewMode.Album)
                },
            )
            // Reels is a touch-first, full-screen vertical-swipe mode — omit it
            // from the desktop menu so pointer users only see Timeline / Album.
            if (!isDesktop()) {
                MomentsViewModeMenuItem(
                    label = stringResource(MR.string.moments_view_reels),
                    icon = Icons.Outlined.Slideshow,
                    isSelected = selected == MomentsViewMode.Reels,
                    onClick = {
                        expanded = false
                        onSelect(MomentsViewMode.Reels)
                    },
                )
            }
        }
    }
}

@Composable
private fun MomentsViewModeMenuItem(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = if (isSelected) {
                    MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        onClick = onClick,
    )
}

internal const val HeartEmoji = "❤️"
internal const val FlameEmoji = "🔥"

// Multi-tap disambiguation window. Slightly longer than Android's stock
// double-tap timeout (~300ms) so the third tap of a triple-tap has comfortable
// headroom on slower devices; short enough that single-tap-to-open doesn't
// feel laggy.
private const val MultiTapTimeoutMs = 320L
private const val FloatingEmojiHideDelayMs = 700L
// Sized to read like Instagram's double-tap heart even though our glyph is
// an emoji (which has internal padding inside the glyph box) — at 140sp the
// visible heart pixels are roughly the dominant element on the post.
private const val FloatingEmojiFontSize = 140

/**
 * Distinguishes "user just added a reaction" from "user just removed one"
 * so the [FloatingReactionOverlay] can play a different animation for each.
 * Same glyph in both cases — only the motion changes.
 */
internal sealed interface FloatingReactionAction {
    val emoji: String
    data class Add(override val emoji: String) : FloatingReactionAction
    data class Remove(override val emoji: String) : FloatingReactionAction
}

/**
 * One trigger of the reaction overlay. The [nonce] increments per call so
 * that even repeated identical actions (Add → Add of the same emoji) push a
 * fresh value into the state flow and retrigger the overlay's per-call
 * animation. Without it, `data class` equality would collapse repeats into
 * a single emission and the overlay would skip the second animation.
 */
internal data class FloatingReactionDisplay(
    val action: FloatingReactionAction,
    val nonce: Long,
)

/**
 * State holder + dispatcher for the reaction overlay. The feed card and the
 * detail screen both [show] reactions through one of these so the
 * confirmation animation is identical on both surfaces.
 *
 * Each call to [show] cancels any pending auto-hide, increments [nonce], and
 * publishes a fresh [FloatingReactionDisplay] — that way an Add immediately
 * followed by a Remove (or vice-versa) restarts the animation cleanly
 * instead of letting the in-flight Add animation continue and masking the
 * Remove.
 */
internal class FloatingReactionController(private val scope: CoroutineScope) {
    var display: FloatingReactionDisplay? by mutableStateOf(null)
        private set
    private var hideJob: Job? = null
    private var nonceCounter: Long = 0L

    fun show(emoji: String, isRemoving: Boolean) {
        hideJob?.cancel()
        val nonce = ++nonceCounter
        val action = if (isRemoving) {
            FloatingReactionAction.Remove(emoji)
        } else {
            FloatingReactionAction.Add(emoji)
        }
        display = FloatingReactionDisplay(action, nonce)
        hideJob = scope.launch {
            delay(FloatingEmojiHideDelayMs)
            // Guard against a newer call superseding us mid-delay — only
            // clear if we're still the latest trigger.
            if (display?.nonce == nonce) display = null
        }
    }
}

@Composable
internal fun rememberFloatingReactionController(): FloatingReactionController {
    val scope = rememberCoroutineScope()
    return remember(scope) { FloatingReactionController(scope) }
}

/**
 * Animated reaction confirmation. Two visually distinct flavors:
 *   - [FloatingReactionAction.Add]: snaps in at scale 0 and springs up with
 *     a low-damping overshoot, settling at 1.0 — reads as "celebratory pop."
 *   - [FloatingReactionAction.Remove]: snaps in at full size 1.0, then
 *     simultaneously shrinks toward [FloatingEmojiRemoveEndScale], drifts
 *     down by [FloatingEmojiRemoveDriftPx], and tilts by
 *     [FloatingEmojiRemoveRotationDeg] before fading out — reads as "the
 *     reaction is falling away."
 *
 * The motion is driven by explicit [Animatable]s rather than the shared
 * `animateFloatAsState` so the Remove path can `snapTo(1f)` at the start of
 * each trigger. That snap is what makes a rapid Add → Remove visibly switch
 * to the shrink-and-drift animation; without it, the scale state lingers
 * from the prior Add and the Remove silently animates from ~1 to ~0.4 with
 * almost no perceived change.
 *
 * The last non-null display is remembered so the glyph stays visible through
 * the exit transition (otherwise AnimatedVisibility unmounts the slot too
 * early and the fade-out is empty).
 */
@Composable
internal fun FloatingReactionOverlay(
    display: FloatingReactionDisplay?,
    modifier: Modifier = Modifier,
) {
    var lastDisplay by remember { mutableStateOf<FloatingReactionDisplay?>(null) }
    if (display != null && display != lastDisplay) {
        lastDisplay = display
    }
    val visible = display != null
    val current = lastDisplay
    val isRemoving = current?.action is FloatingReactionAction.Remove

    val scale = remember { Animatable(0f) }
    val translateY = remember { Animatable(0f) }
    val rotation = remember { Animatable(0f) }

    // Key on `lastDisplay` — which carries the nonce — so every show() call
    // retriggers the animation even when the action type and emoji are
    // identical. Each branch SNAPS its starting values before animating so
    // the new trigger doesn't inherit whatever state the previous one ended
    // on (critical for Add → Remove, where the previous Add had scale=1).
    LaunchedEffect(lastDisplay) {
        val target = lastDisplay ?: return@LaunchedEffect
        when (target.action) {
            is FloatingReactionAction.Remove -> {
                launch {
                    scale.snapTo(1f)
                    scale.animateTo(
                        targetValue = FloatingEmojiRemoveEndScale,
                        animationSpec = tween(
                            durationMillis = FloatingEmojiRemoveAnimMs,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                }
                launch {
                    translateY.snapTo(0f)
                    translateY.animateTo(
                        targetValue = FloatingEmojiRemoveDriftPx,
                        animationSpec = tween(
                            durationMillis = FloatingEmojiRemoveAnimMs,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                }
                launch {
                    rotation.snapTo(0f)
                    rotation.animateTo(
                        targetValue = FloatingEmojiRemoveRotationDeg,
                        animationSpec = tween(
                            durationMillis = FloatingEmojiRemoveAnimMs,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                }
            }
            is FloatingReactionAction.Add -> {
                launch {
                    translateY.snapTo(0f)
                    rotation.snapTo(0f)
                    scale.snapTo(0f)
                    scale.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                }
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        // The dramatic motion happens via the Animatables; AnimatedVisibility
        // just handles mount/unmount fades so the slot doesn't pop in/out
        // abruptly when nothing's been shown yet.
        enter = fadeIn(animationSpec = tween(60)),
        exit = fadeOut(animationSpec = tween(if (isRemoving) 220 else 180)),
        modifier = modifier,
    ) {
        val glyph = current?.action?.emoji
        if (glyph != null) {
            Text(
                text = glyph,
                fontSize = FloatingEmojiFontSize.sp,
                color = if (isRemoving) {
                    Color.White.copy(alpha = 0.8f)
                } else {
                    Color.Unspecified
                },
                modifier = Modifier.graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationY = translateY.value
                    rotationZ = rotation.value
                },
            )
        }
    }
}

// Remove animation tuning. End-scale is the size the glyph shrinks to as it
// "falls"; drift is the downward distance it travels; rotation gives a
// slight tilt so the motion doesn't look like a pure linear shrink. Drift
// scales with [FloatingEmojiFontSize] — a larger glyph needs to travel
// proportionally further before it reads as "leaving."
private const val FloatingEmojiRemoveEndScale = 0.4f
private const val FloatingEmojiRemoveDriftPx = 140f
private const val FloatingEmojiRemoveRotationDeg = 12f
private const val FloatingEmojiRemoveAnimMs = 460
