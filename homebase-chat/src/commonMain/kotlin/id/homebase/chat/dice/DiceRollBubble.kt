package id.homebase.chat.dice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.chat_dice_summary
import id.homebase.resources.chat_dice_unparseable
import org.jetbrains.compose.resources.stringResource

/**
 * In-stream bubble for a [DiceRollDescriptor]. Renders entirely from header data
 * — no payload fetch on scroll. Mirrors [id.homebase.chat.event.EventBubble].
 *
 * `null` descriptor or an invalid one (face count out of range, empty results)
 * collapses to a one-line fallback chip so a bad roll doesn't break the stream.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiceRollBubble(
    descriptor: DiceRollDescriptor?,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(20.dp)

    if (descriptor == null || !descriptor.isValid()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .clip(shape)
                .background(containerColor)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Casino,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(MR.string.chat_dice_unparseable),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
        }
        return
    }

    val critColor = MaterialTheme.colorScheme.tertiary

    Column(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .widthIn(min = 200.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (value in descriptor.results) {
                val isCrit = value == descriptor.faces || value == 1
                val cellModifier = Modifier
                    .size(48.dp)
                    .let {
                        if (isCrit) it
                            .clip(RoundedCornerShape(10.dp))
                            .border(2.dp, critColor, RoundedCornerShape(10.dp))
                        else it
                    }
                Box(modifier = cellModifier, contentAlignment = Alignment.Center) {
                    DiceFaceImage(
                        faces = descriptor.faces,
                        value = value,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (descriptor.source == RollSource.ShakeSeeded) {
                Icon(
                    imageVector = Icons.Filled.Vibration,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = stringResource(
                    MR.string.chat_dice_summary,
                    descriptor.sum,
                    descriptor.results.joinToString("+"),
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
        }
    }
}
