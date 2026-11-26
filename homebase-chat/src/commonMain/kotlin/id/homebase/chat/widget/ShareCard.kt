package id.homebase.chat.widget

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.chat_message_options
import org.jetbrains.compose.resources.stringResource

/**
 * The card a share message shows (files from Filesync, an album from Photos): the app's [icon], a [title] and
 * [subtitle], the kind's own [details], and an Open button. [onOpen] null leaves the card inert, as in a preview.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShareCard(
    icon: ImageVector,
    iconDescription: String?,
    title: @Composable () -> Unit,
    subtitle: String,
    openLabel: String,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    details: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.widthIn(min = 240.dp, max = 320.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .semantics(mergeDescendants = true) {}
                .let {
                    if (onOpen != null) {
                        it.combinedClickable(
                            onClick = onOpen,
                            onClickLabel = openLabel,
                            onLongClick = onLongClick,
                            onLongClickLabel = stringResource(MR.string.chat_message_options),
                        )
                    } else it
                },
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Icon(icon, contentDescription = iconDescription, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    title()
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            details()
            Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onOpen != null) TextButton(onClick = onOpen) { Text(openLabel) } else Spacer(Modifier.size(12.dp))
                Spacer(Modifier.weight(1f))
                footer?.invoke()
            }
        }
    }
}

/** A share card that couldn't be read, with the message's [footer] under it. */
@Composable
internal fun UnparseableShareCard(icon: ImageVector, text: String, modifier: Modifier, footer: (@Composable () -> Unit)?) =
    UnparseableTypedChip(
        icon,
        text,
        modifier,
        footer = footer?.let { f ->
            { Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) { f() } }
        },
    )

@Composable
internal fun ShareDetailLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
