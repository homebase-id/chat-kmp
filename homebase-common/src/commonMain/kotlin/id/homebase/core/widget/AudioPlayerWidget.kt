package id.homebase.core.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.core.audio.AudioPlaybackObserver
import id.homebase.core.audio.getAudioPlayer
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.theme.Dimens
import id.homebase.resources.MR
import id.homebase.resources.audio_pause
import id.homebase.resources.audio_play
import id.homebase.resources.audio_waveform
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
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
            }

            override fun onProgressUpdate(progressSeconds: Int, totalSeconds: Int) {
                Logger.i { "Progress: $progressSeconds / $totalSeconds" }
                currentFileSeconds = progressSeconds
                totalFileSeconds = totalSeconds
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

    // Find the largest waveform thumbnail
    val waveformThumbnail = remember(payload.thumbnails) {
        payload.thumbnails
            ?.filter { it.contentType?.startsWith("image/") == true }
            ?.maxByOrNull { (it.pixelWidth ?: 0) * (it.pixelHeight ?: 0) }
    }

    Column(
        modifier = modifier
            .widthIn(min = Dimens.MediaBubble.minWidthSolo)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp)
    ) {
        // First row: Play button, progress info, slider, overflow menu
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Play/Pause Button
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
                            if (currentFileSeconds == 0) {
                                audioPlayer.play(audioFile)
                            } else {
                                audioPlayer.resume()
                            }
                            isPlaying = true
                        }
                    }
                },
                enabled = (audioFile != null || !fileRequested) && onRequestDecryptedFile != null
            ) {
                if (isLoading && audioFile == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(MR.string.audio_pause) else stringResource(MR.string.audio_play),
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Time display
            Text(
                text = if (totalFileSeconds > 0) formatAudioTime(currentFileSeconds) + " / " + formatAudioTime(totalFileSeconds) else "--:--",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.widthIn(min = 40.dp),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.width(8.dp))

            var progress = if (totalFileSeconds > 0) {
                currentFileSeconds.toFloat() / totalFileSeconds.toFloat()
            } else 0f


            Slider(
                value = progress,
                enabled = onRequestDecryptedFile != null,
                onValueChange = {
                    progress = it
                    audioPlayer.jump((it * totalFileSeconds).toInt())
                },
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp),
                thumb = {
                    // Round thumb
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            )
                    )
                },
                track = { _ ->
                    // Square-edged track
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                    ) {
                        // Inactive track (background)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    shape = RectangleShape
                                )
                        )
                        // Active track (progress)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RectangleShape
                                )
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.width(8.dp))
        }
        // Second row: Waveform visualization
        if (waveformThumbnail != null) {
            Spacer(modifier = Modifier.height(8.dp))
            WaveformImage(
                driveId = driveId,
                fileId = fileId,
                payload = payload,
                thumbnail = waveformThumbnail,
                keyHeader = keyHeader,
                progress = if (totalFileSeconds > 0) currentFileSeconds.toFloat() / totalFileSeconds.toFloat() else 0f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
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

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun WaveformImage(
    driveId: Uuid,
    fileId: Uuid,
    payload: PayloadDescriptor,
    thumbnail: ThumbnailDescriptor,
    keyHeader: KeyHeader,
    progress: Float,
    modifier: Modifier = Modifier
) {
    // Create HomebaseImageData for the waveform thumbnail
    val imageData = remember(driveId, fileId, payload.key, thumbnail.pixelWidth, thumbnail.pixelHeight) {
        val payloadIv = Base64.decode(
            payload.iv ?: throw IllegalStateException("encrypted payload requires key header")
        )

        HomebaseImageData(
            driveId = driveId,
            fileId = fileId,
            payloadKey = payload.key,
            previewThumbnail = thumbnail.toEmbeddedThumb(),
            requestedSize = ImageSize(
                pixelWidth = thumbnail.pixelWidth ?: 320,
                pixelHeight = thumbnail.pixelHeight ?: 60
            ),
            keyHeader = KeyHeader(iv = payloadIv, aesKey = keyHeader.aesKey),
            lastModified = payload.lastModified
        )
    }

    Box(modifier = modifier) {
        // Display the waveform image
        HomebaseImage(
            imageData = imageData,
            contentDescription = stringResource(MR.string.audio_waveform),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(
                color = MaterialTheme.colorScheme.primary,  // or any color you want
                blendMode = BlendMode.SrcIn  // This replaces the original color
            )
        )

//        // Overlay progress indicator
//        Box(
//            modifier = Modifier
//                .fillMaxWidth(progress.coerceIn(0f, 1f))
//                .fillMaxHeight()
//                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
//        )
    }
}

private fun formatAudioTime(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
}