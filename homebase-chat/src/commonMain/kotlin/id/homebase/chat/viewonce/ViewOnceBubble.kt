package id.homebase.chat.viewonce

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.chat_view_once_photo
import id.homebase.resources.chat_view_once_sent
import id.homebase.resources.chat_view_once_unparseable
import id.homebase.resources.chat_view_once_video
import org.jetbrains.compose.resources.stringResource

/** Placeholder only: it never reads the message payload. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ViewOnceBubble(
    descriptor: ViewOnceDescriptor?,
    isOutgoing: Boolean,
    shape: Shape,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    authorName: String? = null,
    authorColor: Color? = null,
    footer: @Composable () -> Unit = {},
) {
    // An unknown descriptor is not this sender's media to invite a tap on, so it drops the accent.
    val badgeContainer = when {
        descriptor == null -> contentColor.copy(alpha = 0.08f)
        isOutgoing -> contentColor.copy(alpha = 0.16f)
        else -> MaterialTheme.colorScheme.primary
    }
    val badgeContent = when {
        descriptor == null -> contentColor.copy(alpha = 0.7f)
        isOutgoing -> contentColor
        else -> MaterialTheme.colorScheme.onPrimary
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .widthIn(min = 208.dp)
            .semantics(mergeDescendants = true) {}
            .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        if (authorName != null) {
            Text(
                text = authorName,
                style = MaterialTheme.typography.labelMedium,
                color = authorColor ?: contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
            )
        }
        FooterTrailingOrBelow(footer = footer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(badgeContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (descriptor == null) ViewOnceIcon else ViewOnceDigitIcon,
                    contentDescription = null,
                    tint = badgeContent,
                    modifier = Modifier.size(if (descriptor == null) 22.dp else 28.dp),
                )
            }
            Column(
                modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (descriptor == null) {
                    Text(
                        text = stringResource(MR.string.chat_view_once_unparseable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.85f),
                    )
                } else {
                    Text(
                        text = stringResource(
                            if (descriptor.kind == ViewOnceDescriptor.KIND_VIDEO) MR.string.chat_view_once_video
                            else MR.string.chat_view_once_photo
                        ),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = contentColor,
                    )
                    Text(
                        text = stringResource(MR.string.chat_view_once_sent),
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor.copy(alpha = 0.72f),
                    )
                }
            }
        }
        }
    }
}

// The timestamp tucks beside the content like a text bubble's, and drops below when that would squeeze it.
@Composable
private fun FooterTrailingOrBelow(footer: @Composable () -> Unit, content: @Composable () -> Unit) {
    Layout(contents = listOf(content, footer)) { (contentMeasurables, footerMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val footerPlaceable = footerMeasurables.firstOrNull()?.measure(loose)
        val footerWidth = footerPlaceable?.width ?: 0
        val footerHeight = footerPlaceable?.height ?: 0
        val gap = if (footerPlaceable == null) 0 else 12.dp.roundToPx()
        val body = contentMeasurables.first()
        val inlineMax = (constraints.maxWidth - footerWidth - gap).coerceAtLeast(0)
        val inline = !constraints.hasBoundedWidth || body.maxIntrinsicWidth(constraints.maxHeight) <= inlineMax
        val bodyPlaceable = body.measure(
            if (inline && constraints.hasBoundedWidth) loose.copy(maxWidth = inlineMax) else loose,
        )
        val width = if (inline) bodyPlaceable.width + gap + footerWidth else maxOf(bodyPlaceable.width, footerWidth)
        val layoutWidth = width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = if (inline) maxOf(bodyPlaceable.height, footerHeight) else bodyPlaceable.height + footerHeight
        layout(layoutWidth, height) {
            bodyPlaceable.placeRelative(0, if (inline) (height - bodyPlaceable.height) / 2 else 0)
            footerPlaceable?.placeRelative(layoutWidth - footerWidth, height - footerHeight)
        }
    }
}
