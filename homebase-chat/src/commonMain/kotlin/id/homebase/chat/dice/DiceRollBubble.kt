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
import id.homebase.resources.chat_dice_battle_and
import id.homebase.resources.chat_dice_battle_leader_other
import id.homebase.resources.chat_dice_battle_leader_self
import id.homebase.resources.chat_dice_battle_tie_other
import id.homebase.resources.chat_dice_battle_tie_self
import id.homebase.resources.chat_dice_summary
import id.homebase.resources.chat_dice_summary_single
import id.homebase.resources.chat_dice_unparseable
import org.jetbrains.compose.resources.stringResource

/**
 * In-stream bubble for a [DiceRollDescriptor]. Renders entirely from header
 * data — no payload fetch, no in-memory chain walk.
 *
 * Battle bubbles compute the leader line directly from the embedded
 * [DiceRollDescriptor.rolls] array — historical bubbles never change as new
 * battles arrive. Standalone rolls (single-entry array) keep the original
 * "You rolled X" line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiceRollBubble(
    descriptor: DiceRollDescriptor?,
    currentOdinId: String = "",
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

    val latest = descriptor.latest
    val critColor = MaterialTheme.colorScheme.tertiary

    // Scale faces with the dice count so a 1-3 die roll renders large (closer
    // to the 256px source, less downscale = crisper) while a 12-die spray fits
    // the bubble width. The cell holds the optional crit border; the face
    // image sits inside with a small inset.
    val cellSize = if (latest.results.size <= 3) 96.dp else 56.dp
    val faceInset = 4.dp

    // Don't fillMaxWidth on the Column or FlowRow — the parent wrapper aligns
    // sent messages to the end and received to the start.
    Column(
        modifier = modifier.padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (value in latest.results) {
                // Crit border is a d20 thing — natural 20 / natural 1 are the
                // only rolls that "matter" outside their numeric value.
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
            if (latest.source == RollSource.ShakeSeeded) {
                Icon(
                    imageVector = Icons.Filled.Vibration,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
            }

            val summaryText = if (descriptor.isBattle) {
                battleLeaderText(descriptor, currentOdinId)
            } else if (latest.results.size == 1) {
                stringResource(MR.string.chat_dice_summary_single, descriptor.sum)
            } else {
                stringResource(
                    MR.string.chat_dice_summary,
                    descriptor.sum,
                    latest.results.joinToString("+"),
                )
            }
            Text(
                text = summaryText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun battleLeaderText(
    descriptor: DiceRollDescriptor,
    currentOdinId: String,
): String {
    val maxSum = descriptor.rolls.maxOf { it.sum }
    val leaders = descriptor.rolls.filter { it.sum == maxSum }
        .map { it.odinId.domainName }
        .distinct()
    val isSelfAmong = currentOdinId.isNotBlank() && currentOdinId in leaders

    return when {
        leaders.size == 1 && isSelfAmong ->
            stringResource(MR.string.chat_dice_battle_leader_self, maxSum)
        leaders.size == 1 ->
            stringResource(
                MR.string.chat_dice_battle_leader_other,
                hostPortion(leaders.first()),
                maxSum,
            )
        else -> {
            val and = stringResource(MR.string.chat_dice_battle_and)
            val displayNames = leaders.map { id ->
                if (id == currentOdinId) "You" else hostPortion(id)
            }
            val joined = joinNames(displayNames, and)
            if (isSelfAmong) {
                stringResource(MR.string.chat_dice_battle_tie_self, joined, maxSum)
            } else {
                stringResource(MR.string.chat_dice_battle_tie_other, joined, maxSum)
            }
        }
    }
}

private fun joinNames(names: List<String>, and: String): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    2 -> "${names[0]} $and ${names[1]}"
    else -> names.dropLast(1).joinToString(", ") + " $and ${names.last()}"
}

/**
 * Display name fallback: the host portion of the odinId — `frodo.dotyou.cloud`
 * → `frodo`. Keeps things readable without a contact-book lookup.
 */
private fun hostPortion(odinId: String): String =
    odinId.substringBefore('.').ifBlank { odinId }
