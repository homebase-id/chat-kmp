package id.homebase.core.ui.screens.moments

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoAlbum
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.auth.initials
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.chat.widget.ExtendPermissionDialog
import id.homebase.core.avatars.AppConnectionStatus
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.OwnerAvatar
import id.homebase.core.avatars.PublicAvatar
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.moments.MomentsAlbumZoom
import id.homebase.core.moments.MomentsViewMode
import id.homebase.core.moments.services.MomentFeedItem
import id.homebase.core.moments.services.MomentSource
import id.homebase.core.ui.screens.moments.widget.MomentDatePill
import id.homebase.core.ui.screens.moments.widget.MomentMediaGallery
import id.homebase.core.ui.screens.moments.widget.MomentUploadProgressOverlay
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
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
import id.homebase.resources.moments_view_timeline
import id.homebase.resources.moments_welcome
import id.homebase.resources.upload_failed_action_delete
import id.homebase.resources.upload_failed_action_dismiss
import id.homebase.resources.upload_failed_sheet_body
import id.homebase.resources.upload_failed_sheet_title
import org.jetbrains.compose.resources.stringResource
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

    if (isWide) {
        WideMomentsLayout(
            moments = uiState.moments,
            uploadProgress = uiState.uploadProgress,
            ownerSession = uiState.ownerSession,
            connectionStatus = uiState.connectionStatus,
            driveIsSyncing = uiState.driveIsSyncing,
            hasDriveError = uiState.hasDriveError,
            viewMode = uiState.viewMode,
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
            ownerSession = uiState.ownerSession,
            connectionStatus = uiState.connectionStatus,
            driveIsSyncing = uiState.driveIsSyncing,
            hasDriveError = uiState.hasDriveError,
            viewMode = uiState.viewMode,
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
            FloatingActionButton(onClick = onCreateMoment) {
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
                selfOdinId = ownerSession?.odinId,
                onOpenMoment = onOpenMoment,
                onAddReaction = onAddReaction,
                openLabel = openLabel,
                selectedMomentId = null,
                onDeleteFailedMoment = onDeleteFailedMoment,
                onDismissUpload = onDismissUpload,
                modifier = contentModifier,
            )
            MomentsViewMode.Album -> MomentsAlbumGrid(
                moments = moments,
                zoom = albumZoom,
                onZoomChange = onAlbumZoomChange,
                onOpenMoment = onOpenMoment,
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
                FloatingActionButton(onClick = onCreateMoment) {
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

@Composable
private fun MomentsFeedList(
    moments: List<MomentFeedItem>,
    uploadProgress: ImmutableMap<Uuid, UploadStatus> = persistentMapOf(),
    selfOdinId: OdinId?,
    onOpenMoment: (momentId: String, payloadKey: String?) -> Unit,
    onAddReaction: (Uuid, String) -> Unit,
    openLabel: String,
    selectedMomentId: Uuid?,
    onDeleteFailedMoment: (Uuid) -> Unit = {},
    onDismissUpload: (Uuid) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        // Chat-style: feed is sorted newest-first by the service, and
        // reverseLayout pins the newest item to the bottom of the
        // viewport. Initial scroll lands on the newest moment; older
        // moments are reached by scrolling up.
        reverseLayout = true,
    ) {
        items(moments, key = { it.id.toString() }) { moment ->
            MomentPostCard(
                moment = moment,
                uploadStatus = uploadProgress[moment.id],
                selfOdinId = selfOdinId,
                isSelected = selectedMomentId != null && moment.id == selectedMomentId,
                onCardClick = { onOpenMoment(moment.id.toString(), null) },
                onAddReaction = { emoji -> onAddReaction(moment.id, emoji) },
                onClickLabel = openLabel,
                onDeleteFailedMoment = { onDeleteFailedMoment(moment.id) },
                onDismissUpload = { onDismissUpload(moment.id) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentPostCard(
    moment: MomentFeedItem,
    uploadStatus: UploadStatus?,
    selfOdinId: OdinId?,
    isSelected: Boolean,
    onCardClick: () -> Unit,
    onAddReaction: (emoji: String) -> Unit,
    onClickLabel: String,
    onDeleteFailedMoment: () -> Unit,
    onDismissUpload: () -> Unit,
) {
    // Local sheet state — only one moment's failed-upload sheet can be open
    // at a time per card, and the sheet's lifetime tracks the card. No need
    // to lift this to the VM (it's pure presentation, no cross-screen
    // implications, no need to survive process death).
    var failedSheetOpen by remember { mutableStateOf(false) }
    val failedSheetState = rememberModalBottomSheetState()

    // Floating-emoji feedback for double/triple-tap reactions. Set on tap,
    // cleared by [floatingEmojiHideJob] after the animation finishes. Job
    // is cancelled on a follow-up tap so the next emoji re-triggers cleanly.
    var floatingEmoji by remember { mutableStateOf<String?>(null) }
    var floatingEmojiHideJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun showFloatingEmoji(emoji: String) {
        floatingEmoji = emoji
        floatingEmojiHideJob?.cancel()
        floatingEmojiHideJob = scope.launch {
            delay(FloatingEmojiHideDelayMs)
            floatingEmoji = null
        }
    }

    // Multi-tap state lives at the composable level (not inside
    // pointerInput's coroutine) so the dispatch coroutine is hosted by the
    // composable's lifecycle and is not coupled to detector restarts.
    var tapCount by remember { mutableStateOf(0) }
    var dispatchJob by remember { mutableStateOf<Job?>(null) }

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
                                1 -> onCardClick()
                                2 -> {
                                    showFloatingEmoji(HeartEmoji)
                                    onAddReaction(HeartEmoji)
                                }
                                else -> if (resolved >= 3) {
                                    showFloatingEmoji(FlameEmoji)
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
                    onCardClick()
                    true
                }
            },
    ) {
        if (moment.payloads.isEmpty()) {
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
        } else {
            // Wrap the gallery so the upload-progress overlay can size to the
            // media only (matchParentSize) — date pill / sender avatar / info
            // chips below stay readable through the scrim because they sit
            // outside this Box on the outer card.
            Box {
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
                    // Inner per-cell click handlers stay disabled in the feed
                    // so the card-level multi-tap detector receives all taps
                    // — that's what makes double/triple-tap-to-react work on
                    // the media area itself (Instagram-style).
                    onMediaClick = null,
                    isUploading = uploadStatus != null,
                )

                if (uploadStatus != null) {
                    MomentUploadProgressOverlay(
                        status = uploadStatus,
                        modifier = Modifier.matchParentSize(),
                        onPermanentFailureTap = { failedSheetOpen = true },
                    )
                }
            }
        }

        // Top-right: localized capture date pill. Sourced from the moment's
        // userDate (which is set from EXIF if any photo had it, else the
        // post-time clock — see MomentComposeViewModel.deriveMomentInstant).
        MomentDatePill(
            timestamp = Instant.fromEpochMilliseconds(moment.userDateMs),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )

        // Top-left: sender avatar for inbound moments. Same "is mine" rule as
        // `isPrivate` below — null senderOdinId or a self-match means this is
        // the user's own post (sender's drive copy is null; the optimistic
        // writer stamps self on the local copy), so we skip the badge to keep
        // the user's own feed visually quiet.
        val sender = moment.senderOdinId
        if (sender != null && sender != selfOdinId) {
            SenderAvatarBadge(
                odinId = sender,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            )
        }

        // Bottom-right: engagement strip + lock indicator. Engagement strip
        // surfaces top reaction emoji + comment count from
        // `moment.reactionPreview` (kept fresh by MomentsFeedService
        // incremental updates) and renders nothing when empty. The lock badge
        // signals a private moment (no recipients) — absence implies shared,
        // which is the common case. Both live on the right so the bottom-left
        // stays clear of the video duration label rendered by
        // MomentMediaItem.
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EngagementStrip(summary = moment.reactionPreview)
            if (moment.isPrivate(selfOdinId)) {
                IndicatorBadge(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = stringResource(MR.string.moments_feed_indicator_private),
                )
            }
        }

        // Floating-emoji overlay: pops + fades in when a double/triple-tap
        // lands a reaction, then fades back out via [floatingEmojiHideJob].
        // Sits centered on the card so the user sees clear confirmation that
        // their multi-tap registered. Drawn after all other overlays so it
        // lands on top of media + badges.
        FloatingReactionOverlay(
            emoji = floatingEmoji,
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

@Composable
private fun SenderAvatarBadge(
    odinId: OdinId,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        PublicAvatar(
            odinId = odinId,
            initials = odinId.toString().firstOrNull()?.toString()?.uppercase(),
            options = AvatarOptions(size = 24.dp, fontSize = 10.sp),
        )
    }
}

@Composable
private fun IndicatorBadge(
    imageVector: ImageVector,
    contentDescription: String? = null,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Inline reaction + comment indicator overlaid on each feed tile. Deliberately
 * subtle:
 *  - Up to three top emoji, no count digits, so the pill stays narrow.
 *  - Same dark-scrim chip as [IndicatorBadge] so the indicator family on a
 *    tile reads as one design.
 *  - Renders nothing when both reactions and comments are absent — empty
 *    moments stay visually quiet.
 *
 * Lives on the bottom-right alongside the lock badge, leaving the bottom-left
 * clear for the video duration label rendered by MomentMediaItem.
 */
@Composable
private fun EngagementStrip(
    summary: ReactionSummary?,
    modifier: Modifier = Modifier,
) {
    val topEmoji = remember(summary) {
        summary?.reactions?.values
            ?.sortedByDescending { it.count }
            ?.mapNotNull { entry ->
                if (entry.count <= 0) return@mapNotNull null
                decodeReactionEmoji(entry.reactionContent)
            }
            ?.take(MaxTopEmoji)
            .orEmpty()
    }
    val commentCount = summary?.totalCommentCount ?: 0
    if (topEmoji.isEmpty() && commentCount <= 0) return

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        topEmoji.forEach { emoji ->
            Text(
                text = emoji,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
        if (commentCount > 0) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Comment,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = commentCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
    }
}

private const val MaxTopEmoji = 3

/**
 * Decode the JSON-wrapped reaction content into its emoji glyph. Same shape
 * the detail screen uses (see `MomentDetailScreen.decodeReactionEmoji`) —
 * duplicated rather than lifted because there's no third caller yet.
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun decodeReactionEmoji(reactionContent: String): String? = runCatching {
    OdinSystemSerializer.deserialize<ReactionContent>(reactionContent).emoji
}.getOrNull()

private const val HeartEmoji = "❤️"
private const val FlameEmoji = "🔥"

// Multi-tap disambiguation window. Slightly longer than Android's stock
// double-tap timeout (~300ms) so the third tap of a triple-tap has comfortable
// headroom on slower devices; short enough that single-tap-to-open doesn't
// feel laggy.
private const val MultiTapTimeoutMs = 320L
private const val FloatingEmojiHideDelayMs = 700L
private const val FloatingEmojiFontSize = 96
private const val FloatingEmojiInitialScale = 0.4f

/**
 * Animated reaction confirmation. Pops in with a spring scale + fade when
 * [emoji] becomes non-null, then fades out when the parent clears it. We
 * remember the last non-null emoji so the glyph stays visible through the
 * exit transition (otherwise AnimatedVisibility unmounts the slot too early
 * and the fade-out is empty).
 */
@Composable
private fun FloatingReactionOverlay(
    emoji: String?,
    modifier: Modifier = Modifier,
) {
    var displayedEmoji by remember { mutableStateOf<String?>(null) }
    if (emoji != null && emoji != displayedEmoji) {
        displayedEmoji = emoji
    }
    val visible = emoji != null
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else FloatingEmojiInitialScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "floating-emoji-scale",
    )
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(120)) +
            scaleIn(initialScale = FloatingEmojiInitialScale),
        exit = fadeOut(animationSpec = tween(180)) +
            scaleOut(targetScale = FloatingEmojiInitialScale),
        modifier = modifier,
    ) {
        val glyph = displayedEmoji
        if (glyph != null) {
            Text(
                text = glyph,
                fontSize = FloatingEmojiFontSize.sp,
                modifier = Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            )
        }
    }
}
