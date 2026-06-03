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
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.common.util.formatBytes
import id.homebase.core.util.formateDateTime
import id.homebase.resources.MR
import id.homebase.resources.image_info_date
import id.homebase.resources.image_info_dimensions
import id.homebase.resources.image_info_title
import id.homebase.resources.image_info_type
import id.homebase.resources.video_info_size
import id.homebase.resources.video_info_unknown
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/**
 * Info panel for an image payload, mirroring [VideoInfoOverlay]. Shown from the
 * moment overflow "Info" menu item when the on-screen item is an image.
 *
 * Unlike video, images carry no rich [id.homebase.api.client.drives.files.DescriptorContent]
 * (there is no `ImageFile`), so the values come straight off the
 * [PayloadDescriptor]: content type, size, and last-modified date. The original
 * pixel dimensions aren't stored, so the resolution line is derived from the
 * largest stored thumbnail and explicitly labelled approximate; it's omitted
 * entirely when no thumbnail dimensions are available.
 */
@Composable
fun ImageInfoOverlay(
    payload: PayloadDescriptor,
    modifier: Modifier = Modifier,
) {
    val unknown = stringResource(MR.string.video_info_unknown)

    val typeVal = payload.contentType
        ?.substringAfterLast('/')
        ?.uppercase()
        ?: unknown
    val sizeVal = payload.bytesWritten?.takeIf { it > 0 }?.let { formatBytes(it) } ?: unknown
    val dateVal = payload.lastModified
        ?.let { formateDateTime(Instant.fromEpochMilliseconds(it)) }
        ?: unknown

    // Best-effort resolution: the largest thumbnail we have. Not the original
    // image's true pixel size, hence the "(approx.)" label on the string.
    val largestThumb = payload.thumbnails
        ?.filter { (it.pixelWidth ?: 0) > 0 && (it.pixelHeight ?: 0) > 0 }
        ?.maxByOrNull { (it.pixelWidth ?: 0) * (it.pixelHeight ?: 0) }
    val dimensionsVal = largestThumb?.let { "${it.pixelWidth}×${it.pixelHeight}" }

    val lines = buildList {
        add(stringResource(MR.string.image_info_type, typeVal))
        if (dimensionsVal != null) {
            add(stringResource(MR.string.image_info_dimensions, dimensionsVal))
        }
        add(stringResource(MR.string.video_info_size, sizeVal))
        add(stringResource(MR.string.image_info_date, dateVal))
    }
    val title = stringResource(MR.string.image_info_title)
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
