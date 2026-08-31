package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.image.MediaQuality
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.cd_media_quality_hd_badge
import org.jetbrains.compose.resources.stringResource

/**
 * True only when the payload explicitly records [MediaQuality.HIGH].
 *
 * A missing quality is "not recorded", not "standard": every photo sent before the flag
 * shipped reads that way, and badging those as standard would mislabel the HD ones. Absent
 * and standard therefore render identically — no badge.
 */
fun PayloadDescriptor.isHighQualityImage(): Boolean =
    (descriptorInfo() as? DescriptorContent.ImageFile)?.quality == MediaQuality.HIGH

/**
 * HD marker for a photo overlaid on the image itself. Carries its own scrim because it sits
 * on arbitrary picture content, where no surface role can guarantee contrast.
 */
@Composable
fun HdBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .background(
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Hd,
            contentDescription = stringResource(MR.string.cd_media_quality_hd_badge),
            tint = HomebaseTheme.extendedColors.bubbleSentOnSurface,
            modifier = Modifier.size(16.dp),
        )
    }
}
