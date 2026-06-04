package id.homebase.common.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.common.util.formatBytes
import id.homebase.resources.MR
import id.homebase.resources.video_info_bit_depth
import id.homebase.resources.video_info_bitrate
import id.homebase.resources.video_info_codec
import id.homebase.resources.video_info_container
import id.homebase.resources.video_info_duration
import id.homebase.resources.video_info_hdr
import id.homebase.resources.video_info_no
import id.homebase.resources.video_info_resolution
import id.homebase.resources.video_info_size
import id.homebase.resources.video_info_title
import id.homebase.resources.video_info_unknown
import id.homebase.resources.video_info_yes
import org.jetbrains.compose.resources.stringResource

/**
 * Hidden debug overlay showing a video's real technical metadata (codec,
 * resolution, bit depth, HDR, bitrate, duration, size). Toggled by long-pressing
 * the MP4/HLS tag on a video (chat bubbles and moments).
 *
 * Values come from [DescriptorContent.VideoFile], populated at encode time from a
 * probe of the compressed output (see VideoPayloadProcessor). Fields the probe
 * couldn't determine — or older payloads predating that capture — render as an
 * em dash.
 *
 * Shared in homebase-common so chat (homebase-chat) and moments (homebase-core)
 * render an identical panel from one place.
 */
@Composable
fun VideoInfoOverlay(
    descriptor: DescriptorContent.VideoFile?,
    isHls: Boolean,
    modifier: Modifier = Modifier,
) {
    val unknown = stringResource(MR.string.video_info_unknown)
    val containerVal = if (isHls) "HLS" else "MP4"
    val codecVal = descriptor?.codec?.uppercase() ?: unknown
    val resolutionVal =
        if (descriptor != null && descriptor.widthPx > 0 && descriptor.heightPx > 0) {
            "${descriptor.widthPx}×${descriptor.heightPx}"
        } else unknown
    val bitDepthVal = descriptor?.bitDepth?.takeIf { it > 0 }?.let { "$it-bit" } ?: unknown
    val hdrVal =
        if (descriptor?.isHdr == true) stringResource(MR.string.video_info_yes)
        else stringResource(MR.string.video_info_no)
    val bitrateVal =
        descriptor?.videoBitrateBps?.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: unknown
    val durationVal = descriptor?.durationMs?.let { formatHms(it) } ?: unknown
    val sizeVal = descriptor?.fileSizeBytes?.takeIf { it > 0 }?.let { formatBytes(it) } ?: unknown

    val lines = listOf(
        stringResource(MR.string.video_info_container, containerVal),
        stringResource(MR.string.video_info_codec, codecVal),
        stringResource(MR.string.video_info_resolution, resolutionVal),
        stringResource(MR.string.video_info_bit_depth, bitDepthVal),
        stringResource(MR.string.video_info_hdr, hdrVal),
        stringResource(MR.string.video_info_bitrate, bitrateVal),
        stringResource(MR.string.video_info_duration, durationVal),
        stringResource(MR.string.video_info_size, sizeVal),
    )
    val title = stringResource(MR.string.video_info_title)
    val body = lines.joinToString("\n")

    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = body,
                color = Color.White,
                fontSize = 11.sp,
            )
        }
    }
}

/** Milliseconds → `m:ss` (or `h:mm:ss` past an hour). Locale-independent. */
private fun formatHms(ms: Long): String {
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
