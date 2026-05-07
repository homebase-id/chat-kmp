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
 * Valid rolls render without a tonal surface, like the emoji-only message path
 * (see `MessageBubbleRaw.kt:213` `emojiOnly`) — the dice faces have their own
 * visual weight and look better floating on the conversation background. The
 * unparseable chip keeps its tonal surface for legibility.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiceRollBubble(
    descriptor: DiceRollDescriptor?,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface

    if (descriptor == null || !descriptor.isValid()) {
        val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .clip(RoundedCornerShape(20.dp))
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

    // Scale faces with the dice count so a 1-3 die roll renders large (closer to
    // the 256px source, less downscale = crisper) while a 12-die spray fits the
    // bubble width. The cell holds the optional crit border; the face image sits
    // inside with a small inset.
    val cellSize = if (descriptor.results.size <= 3) 96.dp else 56.dp
    val faceInset = 4.dp

    // Don't fillMaxWidth on the Column or FlowRow — the parent wrapper aligns
    // sent messages to the end and received to the start. If the bubble spans
    // the full conversation width, that alignment becomes a no-op and the dice
    // appear stuck to the left for everyone.
    Column(
        modifier = modifier.padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (value in descriptor.results) {
                // Crit border is a d20 thing — natural 20 / natural 1 are the
                // only rolls that "matter" outside their numeric value. On
                // smaller dice, hitting min or max is unremarkable.
                val isCrit = descriptor.faces == 20 && (value == 20 || value == 1)
                val cellModifier = Modifier
                    .size(cellSize)
                    .let {
                        if (isCrit) it
                            .clip(RoundedCornerShape(12.dp))
                            .border(2.dp, critColor, RoundedCornerShape(12.dp))
                        else it
                    }
                Box(modifier = cellModifier, contentAlignment = Alignment.Center) {
                    DiceFaceImage(
                        faces = descriptor.faces,
                        value = value,
                        modifier = Modifier.size(cellSize - faceInset * 2),
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
