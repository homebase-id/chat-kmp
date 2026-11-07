package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.image.MediaQuality
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.cd_media_quality_hd_badge
import id.homebase.resources.cd_media_quality_original_badge
import id.homebase.resources.chat_media_quality_hd
import id.homebase.resources.chat_media_quality_original
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The quality to badge a photo with, or null for none.
 *
 * A missing quality is "not recorded", not "standard": every photo sent before the flag shipped
 * reads that way, and badging those as standard would mislabel the rest. Absent and standard
 * therefore render identically, with no badge.
 */
fun PayloadDescriptor.badgedQuality(): MediaQuality? =
    (descriptorInfo() as? DescriptorContent.ImageFile)?.quality?.takeIf { it != MediaQuality.STANDARD }

val MediaQuality.badgeLabel: StringResource
    get() = when (this) {
        MediaQuality.ORIGINAL -> MR.string.chat_media_quality_original
        else -> MR.string.chat_media_quality_hd
    }

/**
 * Quality marker overlaid on a photo. Carries its own scrim because it sits on arbitrary picture
 * content, where no surface role can guarantee contrast.
 */
@Composable
fun MediaQualityBadge(quality: MediaQuality, modifier: Modifier = Modifier) {
    val label = stringResource(quality.badgeLabel)
    val description = stringResource(
        if (quality == MediaQuality.ORIGINAL) MR.string.cd_media_quality_original_badge else MR.string.cd_media_quality_hd_badge
    )
    Box(
        modifier = modifier
            .padding(6.dp)
            .background(
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = HomebaseTheme.extendedColors.bubbleSentOnSurface,
        )
    }
}
