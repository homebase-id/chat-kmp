package id.homebase.core.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.core.audio.VoiceNotePlayback
import id.homebase.core.audio.rememberWaveformAmplitudes
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.util.initials
import id.homebase.resources.MR
import id.homebase.resources.audio_pause
import id.homebase.resources.audio_play
import id.homebase.resources.audio_sender_avatar
import id.homebase.resources.audio_speed
import id.homebase.resources.audio_speed_1_5x
import id.homebase.resources.audio_speed_1x
import id.homebase.resources.audio_speed_2x
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

// 320px is the smallest uploaded waveform raster that still gives the column scan
// ~7px per bar; anything bigger is a pointless fetch.
private const val MIN_WAVEFORM_RASTER_WIDTH = 320

private val PLAYBACK_SPEEDS = floatArrayOf(1f, 1.5f, 2f)

private val SenderAvatarOptions = AvatarOptions(size = Dimens.Message.senderAvatarSize)

private val SENDER_AVATAR_GAP = 8.dp

@Immutable
data class VoiceNoteSender(val odinId: OdinId, val displayName: String)

@Immutable
private data class VoiceNoteBubbleState(
    val isCurrent: Boolean = false,
    val isPlaying: Boolean = false,
    val canResume: Boolean = false,
    val speed: Float = 1f,
    val elapsedSeconds: Int = 0,
    val durationSeconds: Int = 0,
)

@Composable
fun AudioPlayerWidget(
    modifier: Modifier = Modifier,
    fileId: Uuid,
    driveId: Uuid,
    keyHeader: KeyHeader,
    audioFile: String?,
    payload: PayloadDescriptor,
    onRequestDecryptedFile: (() -> Unit)? = null,
    sender: VoiceNoteSender? = null,
) {
    val playback: VoiceNotePlayback = koinInject()
    val coroutineScope = rememberCoroutineScope()
    val playbackKey = remember(fileId, payload.key) { "$fileId-${payload.key}" }

    var fileRequested by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    val descriptorSeconds = remember(payload) {
        (payload.descriptorInfo() as? DescriptorContent.AudioFile)?.lengthSeconds ?: 0
    }

    // Held as a State object and read only inside the Canvas draw lambda below, so a position
    // tick invalidates the waveform's draw pass instead of recomposing every visible bubble.
    val playbackState = playback.state.collectAsStateWithLifecycle()

    val bubble by remember(playback, playbackKey) {
        playback.state.map { state ->
            val current = state.playingKey == playbackKey
            VoiceNoteBubbleState(
                isCurrent = current,
                isPlaying = current && state.isPlaying,
                canResume = current && !state.isPlaying && state.positionMs > 0,
                speed = state.speed,
                elapsedSeconds = if (current) (state.positionMs / 1000).toInt() else 0,
                durationSeconds = if (current) (state.durationMs / 1000).toInt() else 0,
            )
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(VoiceNoteBubbleState())

    val totalSeconds = if (bubble.durationSeconds > 0) bubble.durationSeconds else descriptorSeconds

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

    val livePosition: () -> Float = {
        val state = playbackState.value
        if (state.playingKey == playbackKey && state.durationMs > 0) {
            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
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
        if (sender != null) {
            val senderInitials = remember(sender.displayName) { sender.displayName.initials() }
            val senderLabel = stringResource(MR.string.audio_sender_avatar, sender.displayName)
            // PublicAvatar hard-codes a generic description; clearAndSetSemantics replaces the
            // whole subtree's so a screen reader names the person instead.
            Box(modifier = Modifier.clearAndSetSemantics { contentDescription = senderLabel }) {
                PublicAvatar(
                    odinId = sender.odinId,
                    initials = senderInitials,
                    options = SenderAvatarOptions,
                )
            }
            Spacer(modifier = Modifier.width(SENDER_AVATAR_GAP))
        }

        IconButton(
            onClick = {
                if (audioFile == null && !fileRequested) {
                    fileRequested = true
                    isLoading = true
                    onRequestDecryptedFile?.invoke()
                } else if (audioFile != null) {
                    when {
                        bubble.isPlaying -> playback.pause()
                        bubble.canResume -> playback.resume()
                        else -> coroutineScope.launch { playback.play(playbackKey, audioFile) }
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
                    imageVector = if (bubble.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (bubble.isPlaying) {
                        stringResource(MR.string.audio_pause)
                    } else {
                        stringResource(MR.string.audio_play)
                    },
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        AudioWaveform(
            amplitudes = amplitudes,
            progress = livePosition,
            onSeek = if (bubble.isCurrent) {
                { fraction -> playback.seekTo(fraction) }
            } else {
                null
            },
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(12.dp))

        // The chip sits under the timer rather than beside the waveform: the bubble is capped at
        // 320dp and a fourth column would cost the waveform a third of its bars.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.widthIn(min = 40.dp),
        ) {
            Text(
                text = formatAudioTime(
                    if (bubble.elapsedSeconds > 0) bubble.elapsedSeconds else totalSeconds
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (bubble.isCurrent) {
                Spacer(modifier = Modifier.height(6.dp))
                SpeedChip(
                    speed = bubble.speed,
                    onClick = { playback.setSpeed(nextSpeed(bubble.speed)) },
                )
            }
        }
    }

    LaunchedEffect(audioFile) {
        if (audioFile != null && isLoading) {
            isLoading = false
            playback.play(playbackKey, audioFile)
        }
    }
}

@Composable
private fun SpeedChip(speed: Float, onClick: () -> Unit) {
    val label = when (speed) {
        2f -> stringResource(MR.string.audio_speed_2x)
        1.5f -> stringResource(MR.string.audio_speed_1_5x)
        else -> stringResource(MR.string.audio_speed_1x)
    }
    val description = stringResource(MR.string.audio_speed)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private fun nextSpeed(current: Float): Float {
    val index = PLAYBACK_SPEEDS.indexOfFirst { it == current }
    return PLAYBACK_SPEEDS[(index + 1) % PLAYBACK_SPEEDS.size]
}

private fun formatAudioTime(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
}
