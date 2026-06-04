package id.homebase.core.ui.screens.moments.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar

/**
 * Small dark-translucent circular badge showing a moment author's avatar,
 * rendered in the top-left corner of a feed card and the reel detail. Color /
 * sizing matches the corner date pill and indicator badges so the corners feel
 * like the same family. Shared by [id.homebase.core.ui.screens.moments] feed
 * and detail screens so the two can't drift.
 */
@Composable
internal fun SenderAvatarBadge(
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
