package id.homebase.chat.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import id.homebase.chat.conversationlist.PendingOutgoingMessage
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.pending_attachment_many
import id.homebase.resources.pending_attachment_one
import id.homebase.resources.upload_preparing
import org.jetbrains.compose.resources.stringResource

@Composable
fun PendingMessageBubble(
    message: PendingOutgoingMessage,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(
        topStart = Dimens.Message.cornerRadius,
        topEnd = Dimens.Message.cornerRadius,
        bottomStart = Dimens.Message.cornerRadius,
        bottomEnd = 4.dp,
    )
    val backgroundColor = HomebaseTheme.extendedColors.bubbleSentSurface
    val contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            modifier = Modifier.clip(shape),
            shape = shape,
            color = backgroundColor,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (message.attachmentCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = null,
                            tint = contentColor.copy(alpha = 0.75f),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (message.attachmentCount == 1)
                                stringResource(MR.string.pending_attachment_one)
                            else
                                stringResource(MR.string.pending_attachment_many, message.attachmentCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor.copy(alpha = 0.75f),
                        )
                    }
                }
                if (message.text.isNotEmpty()) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(MR.string.upload_preparing),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}
