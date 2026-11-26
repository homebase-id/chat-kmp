package id.homebase.core.avatars

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.resources.MR
import id.homebase.resources.moments_detail_shared_with_more
import org.jetbrains.compose.resources.stringResource

/** [people]'s avatars overlapping in a row, the first [maxVisible] of them and a "+N" after. */
@Composable
fun AvatarStack(
    people: List<OdinId>,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 24.dp,
    maxVisible: Int = 3,
) {
    if (people.isEmpty()) return
    val visible = people.take(maxVisible)
    val overflow = people.size - visible.size
    // A ring of the surface colour around each, so the overlap reads as separate faces.
    val ringSize = avatarSize + 2.dp
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(-(avatarSize / 3)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visible.forEach { person ->
            Box(
                modifier = Modifier.size(ringSize).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                IdentityAvatar(person, AvatarOptions(size = avatarSize))
            }
        }
        if (overflow > 0) {
            Box(
                modifier = Modifier.size(ringSize).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(MR.string.moments_detail_shared_with_more, overflow),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
