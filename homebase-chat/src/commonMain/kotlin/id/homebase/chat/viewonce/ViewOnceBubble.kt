package id.homebase.chat.viewonce

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.chat_view_once_photo
import id.homebase.resources.chat_view_once_sent
import id.homebase.resources.chat_view_once_unparseable
import id.homebase.resources.chat_view_once_video
import org.jetbrains.compose.resources.stringResource

/** Placeholder chip only: it never reads the message payload. */
@Composable
fun ViewOnceBubble(
    descriptor: ViewOnceDescriptor?,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .widthIn(min = 160.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ViewOnceIcon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        if (descriptor == null) {
            Text(
                text = stringResource(MR.string.chat_view_once_unparseable),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (isOutgoing) {
                    Text(
                        text = stringResource(MR.string.chat_view_once_sent),
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor.copy(alpha = 0.75f),
                    )
                }
                Text(
                    text = stringResource(
                        if (descriptor.kind == ViewOnceDescriptor.KIND_VIDEO) MR.string.chat_view_once_video
                        else MR.string.chat_view_once_photo
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                )
            }
        }
    }
}
