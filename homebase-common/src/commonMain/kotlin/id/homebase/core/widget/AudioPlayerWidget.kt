package id.homebase.core.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.audio.AudioPlaybackObserver
import id.homebase.core.audio.getAudioPlayer
import id.homebase.core.audio.rememberWaveformAmplitudes
import id.homebase.core.ui.theme.Dimens
import id.homebase.resources.MR
import id.homebase.resources.audio_pause
import id.homebase.resources.audio_play
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

// 320px is the smallest uploaded waveform raster that still gives the column scan
// ~7px per bar; anything bigger is a pointless fetch.
private const val MIN_WAVEFORM_RASTER_WIDTH = 320

@Composable
fun AudioPlayerWidget(
    modifier: Modifier = Modifier,
    fileId: Uuid,
    driveId: Uuid,
    keyHeader: KeyHeader,
    audioFile: String?,
    payload: PayloadDescriptor,
    onRequestDecryptedFile: (() -> Unit)? = null,
) {
    val audioPlayer = remember { getAudioPlayer() }
    var isPlaying by remember { mutableStateOf(false) }
    var currentFileSeconds by remember { mutableStateOf(0) }
    var totalFileSeconds by remember { mutableStateOf(0) }
    var fileRequested by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }

    when (val info = payload.descriptorInfo()) {
        is DescriptorContent.AudioFile -> {
            totalFileSeconds = info.lengthSeconds
        }
        else -> {}
    }

    LaunchedEffect(Unit) {
        audioPlayer.setPlaybackObserver(object : AudioPlaybackObserver {
            override fun onComplete() {
                isPlaying = false
                completed = true
                currentFileSeconds = 0
            }

            override fun onProgressUpdate(progressSeconds: Int, totalSeconds: Int) {
                // A tick can land up to one interval after onComplete; it must not undo the reset.
                if (!completed) currentFileSeconds = progressSeconds
                // 0 means the player couldn't determine a duration (web: a stream muxed with no
                // duration box) — keep the length the payload descriptor already gave us.
                if (totalSeconds > 0) totalFileSeconds = totalSeconds
            }
        })
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            audioPlayer.stop()
            audioPlayer.release()
        }
    }

    val waveformThumbnail = remember(payload.thumbnails) {
        val images = payload.thumbnails
            ?.filter { it.contentType?.startsWith("image/") == true }
            .orEmpty()
        images.filter { (it.pixelWidth ?: 0) >= MIN_WAVEFORM_RASTER_WIDTH }
            .minByOrNull { it.pixelWidth ?: 0 }
            ?: images.maxByOrNull { (it.pixelWidth ?: 0) * (it.pixelHeight ?: 0) }
    }

    val amplitudes = if (waveformThumbnail != null) {
        rememberWaveformAmplitudes(
            driveId = driveId,
            fileId = fileId,
            payload = payload,
            thumbnail = waveformThumbnail,
            keyHeader = keyHeader,
        )
    } else {
        null
    }

    // Whole seconds: AudioPlaybackObserver carries no finer resolution, so the head steps
    // once a second. Smoothing needs millisecond progress, not interpolation on top of this.
    val livePosition: () -> Float = {
        if (totalFileSeconds > 0) {
            (currentFileSeconds.toFloat() / totalFileSeconds).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .widthIn(
                min = Dimens.MediaBubble.audioMinWidth,
                max = Dimens.MediaBubble.audioMaxWidth,
            )
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        IconButton(
            onClick = {
                if (audioFile == null && !fileRequested) {
                    fileRequested = true
                    isLoading = true
                    onRequestDecryptedFile?.invoke()
                } else if (audioFile != null) {
                    if (isPlaying) {
                        audioPlayer.pause()
                        isPlaying = false
                    } else {
                        if (completed || currentFileSeconds == 0) {
                            completed = false
                            currentFileSeconds = 0
                            audioPlayer.play(audioFile)
                        } else {
                            audioPlayer.resume()
                        }
                        isPlaying = true
                    }
                }
            },
            enabled = (audioFile != null || !fileRequested) && onRequestDecryptedFile != null,
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        ) {
            if (isLoading && audioFile == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(MR.string.audio_pause) else stringResource(MR.string.audio_play),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        AudioWaveform(
            amplitudes = amplitudes,
            progress = livePosition,
            onSeek = if (audioFile != null && totalFileSeconds > 0) {
                { fraction ->
                    val target = (fraction * totalFileSeconds).toInt()
                    completed = false
                    currentFileSeconds = target
                    audioPlayer.jump(target)
                }
            } else {
                null
            },
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = formatAudioTime(
                if (currentFileSeconds > 0) currentFileSeconds else totalFileSeconds
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.widthIn(min = 36.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Update loading state when file is loaded
    LaunchedEffect(audioFile) {
        if (audioFile != null) {
            if (isLoading) {
                isLoading = false
                isPlaying = true
                audioPlayer.play(audioFile)
            }
        }
    }
}

private fun formatAudioTime(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
}
