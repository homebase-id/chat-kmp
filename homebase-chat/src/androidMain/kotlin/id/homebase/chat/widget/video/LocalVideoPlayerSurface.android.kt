package id.homebase.chat.widget.video

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
actual fun LocalVideoPlayerSurface(
    filePath: String,
    modifier: Modifier,
    onFirstFrameRendered: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(filePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(filePath.toUri()))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(filePath) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                onFirstFrameRendered()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                    player.pause()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                this.useController = true
            }
        },
        modifier = modifier,
    )
}

@Composable
actual fun TrimmableVideoPlayerSurface(
    filePath: String,
    clipStartMs: Long,
    clipEndMs: Long,
    isPlaying: Boolean,
    seekRequestMs: Long?,
    onPositionMs: (Long) -> Unit,
    modifier: Modifier,
    onFirstFrameRendered: () -> Unit,
) {
    val context = LocalContext.current
    val onPositionMsState = rememberUpdatedState(onPositionMs)

    val player = remember(filePath) {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = isPlaying
            // controls are drawn by the trim screen; PlayerView below has them off.
        }
    }

    // Rebuild MediaItem clip whenever the trim range changes; ExoPlayer applies it
    // on prepare without re-buffering the underlying source.
    DisposableEffect(filePath, clipStartMs, clipEndMs) {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(clipStartMs)
            .setEndPositionMs(clipEndMs)
            .build()
        val item = MediaItem.Builder()
            .setUri(filePath.toUri())
            .setClippingConfiguration(clipping)
            .build()
        player.setMediaItem(item)
        player.prepare()

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                onFirstFrameRendered()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    // Loop within clip range
                    player.seekTo(0)
                    player.playWhenReady = true
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    DisposableEffect(filePath) {
        onDispose { player.release() }
    }

    // Apply external play/pause
    LaunchedEffect(isPlaying) {
        player.playWhenReady = isPlaying
    }

    // External seek requests — ExoPlayer treats clip as 0-based, so we subtract clipStart.
    LaunchedEffect(seekRequestMs) {
        seekRequestMs?.let { ms ->
            val rel = (ms - clipStartMs).coerceAtLeast(0L)
            player.seekTo(rel)
        }
    }

    // ~30 Hz position pump via the main looper.
    DisposableEffect(filePath) {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                val absPos = player.currentPosition + clipStartMs
                onPositionMsState.value(absPos)
                handler.postDelayed(this, 33)
            }
        }
        handler.post(runnable)
        onDispose { handler.removeCallbacks(runnable) }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                this.useController = false
            }
        },
        modifier = modifier,
    )
}
