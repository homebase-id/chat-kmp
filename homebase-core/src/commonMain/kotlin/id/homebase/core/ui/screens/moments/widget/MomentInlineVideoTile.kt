package id.homebase.core.ui.screens.moments.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.image.toImageBitmap
import id.homebase.api.video.VideoPlayerData
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.widget.video.VideoPlayerSurface
import id.homebase.core.image.HomebaseImage
import id.homebase.core.moments.services.MomentsVideoSession
import org.koin.compose.koinInject
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.resources.MR
import id.homebase.resources.chat_message_play_video
import id.homebase.resources.chat_message_video_thumbnail
import id.homebase.resources.moment_video_fill_screen
import id.homebase.resources.moment_video_fit_screen
import id.homebase.resources.moment_video_mute
import id.homebase.resources.moment_video_pause
import id.homebase.resources.moment_video_unmute
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * How play/pause taps are received on a [MomentInlineVideoTile].
 *
 * - [FullTile]: the existing single-video card behaviour. The entire thumbnail
 *   is a tap target for play, and the platform's native player controls handle
 *   pause/seek when playing. Native controls swallow horizontal drag, which is
 *   fine when no parent gesture (e.g. carousel pager) is competing for it.
 * - [ButtonOnly]: the carousel-friendly mode. Only a small centred IconButton
 *   responds to taps for play (and a centred Pause IconButton when playing).
 *   The rest of the surface ignores pointer events so the parent
 *   [androidx.compose.foundation.pager.HorizontalPager] gets clean access to
 *   horizontal drags. Native player controls are disabled while playing.
 */
enum class MomentVideoTapMode {
    FullTile,
    ButtonOnly,
}

/**
 * Tap-to-play tile for a moment's video payload. The idle branch shows the same
 * thumbnail + play overlay as [MomentMediaItem]; tapping it flips to
 * [VideoPlayerSurface] in place. Active-playback coordination (one tile playing
 * at a time, pause-on-scroll-off) is owned by the caller.
 *
 * `tapMode` controls how taps reach this tile — see [MomentVideoTapMode] for
 * the swipe-vs-tap trade-off.
 */
@Composable
fun MomentInlineVideoTile(
    payload: PayloadDescriptor,
    fileId: Uuid,
    driveId: Uuid,
    keyHeader: KeyHeader,
    previewThumbnail: EmbeddedThumb? = null,
    localContext: LocalAttachmentContext? = null,
    isUploading: Boolean = false,
    isPlaying: Boolean,
    onPlayTap: () -> Unit,
    onDoubleTap: () -> Unit,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    modifier: Modifier = Modifier,
    tapMode: MomentVideoTapMode = MomentVideoTapMode.FullTile,
    /**
     * When true AND [isPlaying] AND [tapMode] == [MomentVideoTapMode.ButtonOnly]
     * AND [useNativeControls] is false, render a centred Pause IconButton over
     * the player surface so the user can stop playback with a tap. Off by
     * default — the moments feed's autoplay flow deliberately omits any
     * play/pause overlay during playback (scroll-away handles pause). The
     * detail screen has no scroll-away; it gets pause + a scrub bar from
     * [useNativeControls] instead.
     */
    showPauseAffordance: Boolean = false,
    /**
     * Hand playback UI to the platform's native player view — Android
     * PlayerView (with its play/pause + scrubber + time labels that auto-hide
     * after a few seconds), iOS AVPlayerViewController (same idea). Off in
     * the feed because the card-level multi-tap detector needs every touch
     * (double/triple-tap reactions) and the carousel pager needs clean
     * horizontal drag. On in the detail screen, where neither of those
     * constraints applies and the user wants to scrub.
     *
     * Also flips the player surface to letterbox-fit (matching the rest of
     * the detail screen's still-image presentation) and switches the
     * thumbnail underlay to [ContentScale.Fit] so the thumbnail → first-frame
     * transition doesn't visibly re-crop the image.
     */
    useNativeControls: Boolean = false,
    /**
     * When [useNativeControls] is true, swap the default fit-with-letterbox
     * for crop-to-fill (Reels-style). Also flips the thumbnail underlay back
     * to [ContentScale.Crop] so the thumbnail → first-frame transition stays
     * seamless. The detail screen wires this to a session-scoped toggle so
     * the user can flip it via the corner icon below; the feed carousel
     * ignores it (it's always crop-to-fill).
     */
    useZoomFill: Boolean = false,
    /**
     * Tapped when the user wants to toggle [useZoomFill]. When non-null
     * AND [useNativeControls] is true, an aspect-toggle icon is rendered
     * next to the mute icon so the user can flip fit ↔ crop on the fly.
     */
    onToggleZoomFill: (() -> Unit)? = null,
) {
    val payloadIv = remember(payload.iv) { payload.iv?.let { Base64.decode(it) } }
    if (payloadIv == null) {
        MomentVideoIvMissingFallback(
            payload = payload,
            localContext = localContext,
            modifier = modifier,
        )
        return
    }

    val perPayloadKeyHeader = remember(payloadIv, keyHeader.aesKey) {
        KeyHeader(iv = payloadIv, aesKey = keyHeader.aesKey)
    }
    val videoPlayerData = remember(fileId, driveId, payload.key, perPayloadKeyHeader, payload.descriptorContent) {
        VideoPlayerData(
            fileId = fileId,
            driveId = driveId,
            payloadKey = payload.key,
            keyHeader = perPayloadKeyHeader,
            descriptorContent = payload.descriptorContent,
        )
    }
    val videoDescriptor = remember(payload.descriptorContent) {
        payload.descriptorInfo() as? DescriptorContent.VideoFile
    }
    val isHls = videoDescriptor?.isSegmented == true
    val videoLocalContext = localContext as? LocalAttachmentContext.Video
    val displayDurationMs: Long? = run {
        val ctx = videoLocalContext
        if (ctx != null) {
            val total = ctx.durationMs ?: 0L
            val s = ctx.trimStartMs ?: 0L
            val e = ctx.trimEndMs ?: total
            (e - s).takeIf { it > 0 }
        } else videoDescriptor?.durationMs
    }

    val isButtonOnly = tapMode == MomentVideoTapMode.ButtonOnly

    // Feed↔detail playback handoff. The session holds at most one entry — the
    // most recently-playing inline video — keyed by (fileId, payloadKey).
    //
    // - On compose, snapshot any matching position into `startPositionMs`.
    //   `remember(fileId, payload.key)` makes this a one-shot read per mount:
    //   we don't want to keep snapping the player back as the user actually
    //   plays past the saved position.
    // - While playing, push current position back into the session every
    //   ~500 ms via `onPositionUpdate`, so the next mount (feed→detail or
    //   detail→feed) finds a fresh handoff.
    val videoSession = koinInject<MomentsVideoSession>()
    val startPositionMs = remember(fileId, payload.key) {
        videoSession.readPlaybackPosition(fileId, payload.key) ?: 0L
    }

    var isPreloading by remember(fileId, payload.key) { mutableStateOf(false) }
    var preloadProgress by remember(fileId, payload.key) { mutableFloatStateOf(0f) }
    if (!isUploading) {
        VideoPreloadEffect(
            data = videoPlayerData,
            onPreloading = { isPreloading = it },
            onProgress = { preloadProgress = it },
        )
    }
    val imageData = remember(driveId, fileId, payload.key, payload.lastModified) {
        HomebaseImageData(
            driveId = driveId,
            fileId = fileId,
            payloadKey = payload.key,
            previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb()
                ?: previewThumbnail,
            requestedSize = ImageSize.THUMB_MEDIUM,
            lastModified = payload.lastModified,
            isEncrypted = true,
            keyHeader = perPayloadKeyHeader,
        )
    }
    val uploadBitmap = videoLocalContext?.thumbnailBytes?.let { bytes ->
        remember(bytes) { bytes.toImageBitmap() }
    }
    // Carousel (ButtonOnly) mode skips the full-tile tap detector entirely
    // so horizontal drag gestures reach the parent pager cleanly. Play is
    // gated through the centred IconButton below; double-tap-heart bubbles
    // to the card's outer multi-tap detector. When playing, the thumbnail
    // is purely a backdrop — the gesture is suppressed regardless of mode.
    val thumbnailModifier = if (!isPlaying && !isButtonOnly) {
        Modifier
            .fillMaxSize()
            .pointerInput(onPlayTap, onDoubleTap) {
                detectTapGestures(
                    onTap = { onPlayTap() },
                    onDoubleTap = { onDoubleTap() },
                )
            }
    } else {
        Modifier.fillMaxSize()
    }

    // Tracks whether the underlying player has pushed its first decoded
    // frame to the surface. Drives the thumbnail-overlay drop below. Reset
    // on every isPlaying transition so a swipe-away-and-back lands on a
    // fresh "no frame yet → thumbnail covers player" state.
    var firstFramePainted by remember(payload.key, isPlaying) { mutableStateOf(false) }

    Box(modifier = modifier) {
        // Layer 1: video surface (only while playing). Rendered FIRST so it
        // sits at the bottom of the stack; the thumbnail above covers it
        // until the first decoded frame is on screen.
        if (isPlaying) {
            val fullScreenData = remember(videoPlayerData, payload) {
                FullScreenOverlay.VideoPlayerData(
                    fileId = videoPlayerData.fileId,
                    driveId = videoPlayerData.driveId,
                    payloadKey = videoPlayerData.payloadKey,
                    keyHeader = videoPlayerData.keyHeader,
                    payload = payload,
                )
            }
            val haptic = LocalHapticFeedback.current
            VideoPlayerSurface(
                data = fullScreenData,
                modifier = Modifier
                    .fillMaxSize()
                    // Long-press on a playing tile pauses it. Native controls
                    // are universally disabled below, so this is the only
                    // manual pause path (autoplay otherwise pauses on
                    // scroll-off).
                    //
                    // We deliberately do NOT use `detectTapGestures` here:
                    // `detectTapGestures` consumes the up event for every
                    // gesture it observes (even with only `onLongPress`
                    // registered), which would block the card-level
                    // multi-tap detector in MomentsScreen from seeing the
                    // single/double/triple taps that drive open-detail and
                    // heart/flame reactions on the playing tile.
                    //
                    // Using the lower-level `awaitLongPressOrCancellation`
                    // observes events without consuming them — short taps,
                    // double-taps and triple-taps fall straight through to
                    // the parent. We only consume *after* a real long-press
                    // has fired (the long-press change + every subsequent
                    // event until release), so the parent doesn't also
                    // register a tap when the user lifts their finger.
                    .pointerInput(onPlayTap, haptic) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val longPressed = awaitLongPressOrCancellation(down.id)
                                ?: return@awaitEachGesture
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayTap()
                            longPressed.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: break
                                change.consume()
                                if (!change.pressed) break
                            }
                        }
                    },
                muted = isMuted,
                // Native controls (Android PlayerView / iOS AVPlayerViewController)
                // intercept touches on the surface — Android PlayerView's
                // onTouchEvent + setClickable(true) consume ACTION_DOWN and iOS
                // AVPlayerViewController's transport UI does the equivalent —
                // so the moments feed keeps this off so MomentPostCard's outer
                // multi-tap detector can see double/triple taps for reactions.
                // The detail screen opts in via [useNativeControls] above
                // because it wants the auto-hiding play/pause + scrubber.
                useNativeControls = useNativeControls,
                // Dark-launch flag for the iOS inline optimizations (warm
                // AVPlayer pool, audio-track disable when muted, first-frame
                // KVO gate). Off-by-default on the expect signature, so
                // FullScreenVideoPlayer (chat + moments detail) stays on the
                // existing path. Flip this off if a regression shows up only
                // on inline tiles.
                useInlineOptimizations = true,
                startPositionMs = startPositionMs,
                onPositionUpdate = { ms ->
                    videoSession.savePlaybackPosition(fileId, payload.key, ms)
                },
                onFirstFrame = { firstFramePainted = true },
                useZoomFill = useZoomFill,
            )
        }

        // Layer 2: thumbnail. Always mounted across the isPlaying transition
        // so Compose doesn't recompose it from scratch on swipe-back. While
        // the video is loading and during decoder warm-up it sits *over* the
        // player surface, hiding the empty/uninitialized texture. We drop it
        // (zero alpha) once VideoPlayerSurface's onFirstFrame callback has
        // fired, revealing the now-painting video.
        //
        // We deliberately put the thumbnail above the player rather than
        // below + isOpaque=false — that approach blends the GL texture's
        // (possibly uninitialized) alpha channel against the thumbnail, and
        // on some Android GPUs that produces visible horizontal slices of
        // the thumbnail bleeding through the first decoded frame.
        val thumbnailAlpha = if (isPlaying && firstFramePainted) 0f else 1f
        // Match the player's resize mode so the thumbnail → first-frame
        // hand-off doesn't visibly re-crop the image:
        //   - Native controls, no zoom-fill → RESIZE_MODE_FIT → Fit.
        //   - Native controls, zoom-fill → RESIZE_MODE_ZOOM → Crop.
        //   - No native controls (feed carousel) → RESIZE_MODE_ZOOM → Crop.
        val thumbnailContentScale = if (useNativeControls && !useZoomFill) {
            ContentScale.Fit
        } else {
            ContentScale.Crop
        }
        if (uploadBitmap != null) {
            Image(
                bitmap = uploadBitmap,
                contentDescription = stringResource(MR.string.chat_message_video_thumbnail),
                modifier = thumbnailModifier.alpha(thumbnailAlpha),
                contentScale = thumbnailContentScale,
            )
        } else {
            HomebaseImage(
                imageData = imageData,
                modifier = thumbnailModifier.alpha(thumbnailAlpha),
                contentScale = thumbnailContentScale,
                contentDescription = stringResource(MR.string.chat_message_video_thumbnail),
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }

        // Layer 3: state-specific overlays.
        if (isPlaying) {
            // No centred play/pause overlay while playing in the feed —
            // autoplay's raison d'être is "the video just plays" and
            // scroll-away handles pause. The detail screen has no
            // scroll-away, so it sets [showPauseAffordance] = true to get a
            // centred Pause button back.
            if (showPauseAffordance && isButtonOnly) {
                IconButton(
                    onClick = onPlayTap,
                    modifier = Modifier.align(Alignment.Center).size(56.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PauseCircle,
                        contentDescription = stringResource(MR.string.moment_video_pause),
                        modifier = Modifier.size(48.dp),
                        tint = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
            val muteLabel = stringResource(
                if (isMuted) MR.string.moment_video_unmute else MR.string.moment_video_mute,
            )
            IconButton(
                onClick = onToggleMute,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // Sit below the card's TopEnd date pill (which is rendered
                    // by MomentPostCard's outer Box). Without this extra
                    // vertical offset the two top-right elements stack on
                    // top of each other.
                    .padding(top = 48.dp, end = 8.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)),
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff
                    else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = muteLabel,
                    tint = Color.White,
                )
            }
            // Aspect-ratio toggle. Only meaningful when the host owns the
            // resize choice (useNativeControls = true on the detail screen);
            // the feed carousel always crops to fill its locked aspect, so
            // there's nothing to toggle there. The icon shows the action the
            // tap will take, not the current state: Fullscreen ↔ "tap to
            // crop-to-fill"; FullscreenExit ↔ "tap to fit-with-letterbox."
            val toggleZoom = onToggleZoomFill
            if (useNativeControls && toggleZoom != null) {
                val zoomLabel = stringResource(
                    if (useZoomFill) MR.string.moment_video_fit_screen
                    else MR.string.moment_video_fill_screen,
                )
                IconButton(
                    onClick = toggleZoom,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // Stack directly below the mute button. 48dp (mute's
                        // top inset) + 48dp (icon-button hit target) = 96dp
                        // — pushes this one fully below without overlap.
                        .padding(top = 96.dp, end = 8.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)),
                ) {
                    Icon(
                        imageVector = if (useZoomFill) Icons.Default.FullscreenExit
                        else Icons.Default.Fullscreen,
                        contentDescription = zoomLabel,
                        tint = Color.White,
                    )
                }
            }
        } else if (!isUploading) {
            // Idle-state decorations: play affordance, codec badge, duration.
            if (isButtonOnly) {
                IconButton(
                    onClick = onPlayTap,
                    modifier = Modifier.align(Alignment.Center).size(56.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = stringResource(MR.string.chat_message_play_video),
                        modifier = Modifier.size(48.dp),
                        tint = Color.White.copy(alpha = 0.85f),
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center),
                    tint = Color.White.copy(alpha = 0.85f),
                )
            }
            Text(
                text = if (isHls) "HLS" else "MP4",
                color = Color.White,
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
            if (displayDurationMs != null) {
                Text(
                    text = formatDurationLabel(displayDurationMs),
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(
                            Color.Black.copy(alpha = 0.55f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        // Preload progress only meaningful while idle; once the user has
        // tapped play, VideoPlayerSurface owns the loading affordance.
        if (isPreloading && !isUploading && !isPlaying) {
            Box(
                modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                if (preloadProgress > 0f) {
                    CircularProgressIndicator(
                        progress = { preloadProgress },
                        modifier = Modifier.size(40.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MomentVideoIvMissingFallback(
    payload: PayloadDescriptor,
    localContext: LocalAttachmentContext?,
    modifier: Modifier,
) {
    val videoCtx = localContext as? LocalAttachmentContext.Video
    val imageBitmap = videoCtx?.thumbnailBytes?.let { bytes ->
        remember(bytes) { bytes.toImageBitmap() }
    }
    val durationMs: Long? = videoCtx?.let {
        val total = it.durationMs ?: 0L
        val s = it.trimStartMs ?: 0L
        val e = it.trimEndMs ?: total
        (e - s).takeIf { d -> d > 0 }
    }
    if (imageBitmap != null) {
        Box(modifier = modifier) {
            Image(
                bitmap = imageBitmap,
                contentDescription = stringResource(MR.string.chat_message_video_thumbnail),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (durationMs != null) {
                Text(
                    text = formatDurationLabel(durationMs),
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(
                            Color.Black.copy(alpha = 0.55f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    } else {
        Box(modifier = modifier)
    }
}
