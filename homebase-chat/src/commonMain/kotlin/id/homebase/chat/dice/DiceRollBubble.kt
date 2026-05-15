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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.chat_dice_battle_and
import id.homebase.resources.chat_dice_battle_leader_other
import id.homebase.resources.chat_dice_battle_leader_self
import id.homebase.resources.chat_dice_battle_tie_other
import id.homebase.resources.chat_dice_battle_tie_self
import id.homebase.resources.chat_dice_battle_tie_winner_other
import id.homebase.resources.chat_dice_battle_tie_winner_self
import id.homebase.resources.chat_dice_battle_winner_other
import id.homebase.resources.chat_dice_battle_winner_self
import id.homebase.resources.chat_dice_summary
import id.homebase.resources.chat_dice_summary_other
import id.homebase.resources.chat_dice_summary_other_single
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
    chainCap: Int? = null,
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
    // sent messages to the end and received to the start. Inside the column we
    // do center on the cross-axis so the sender / battle-result text sits
    // centered below the dice.
    Column(
        modifier = modifier.padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (descriptor.mode == DiceRollMode.OpenEndedD100) {
            // One row per percentile pair. A chain of high or low rolls stacks
            // vertically (matching the user's "newline if it keeps rolling"
            // suggestion). Dice scale to the same large/compact rule as
            // standard rolls so the first-pair bubble matches a regular d10.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var i = 0
                while (i < latest.results.size - 1) {
                    val tens = latest.results[i]
                    val ones = latest.results[i + 1]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(modifier = Modifier.size(cellSize), contentAlignment = Alignment.Center) {
                            PercentileTensFaceImage(
                                value = tens,
                                modifier = Modifier.size(cellSize - faceInset * 2),
                            )
                        }
                        Box(modifier = Modifier.size(cellSize), contentAlignment = Alignment.Center) {
                            DiceFaceImage(
                                faces = 10,
                                value = ones,
                                modifier = Modifier.size(cellSize - faceInset * 2),
                            )
                        }
                    }
                    i += 2
                }
            }
        } else {
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
        }

        Spacer(Modifier.height(2.dp))

        val lines = computeDiceBubbleLines(descriptor, currentOdinId, chainCap)

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
            Text(
                text = senderLineText(descriptor, lines),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                textAlign = TextAlign.Center,
            )
        }

        // Battle bubbles also show who's leading / who won across the chain.
        // For standalone rolls the sender line above is the whole story.
        lines.battleResult?.let { result ->
            Text(
                text = battleResultText(result),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun senderLineText(descriptor: DiceRollDescriptor, lines: DiceBubbleLines): String {
    val isOe = descriptor.mode == DiceRollMode.OpenEndedD100
    val showDetail: Boolean
    val detail: String
    if (isOe) {
        val pairs = percentilePairs(lines.senderResults)
        showDetail = pairs.size > 1
        detail = formatOeDetail(pairs)
    } else {
        showDetail = lines.senderResults.size > 1
        detail = lines.senderResults.joinToString("+")
    }
    return when {
        lines.senderIsSelf && showDetail ->
            stringResource(MR.string.chat_dice_summary, lines.senderSum, detail)
        lines.senderIsSelf ->
            stringResource(MR.string.chat_dice_summary_single, lines.senderSum)
        showDetail ->
            stringResource(
                MR.string.chat_dice_summary_other,
                lines.senderName,
                lines.senderSum,
                detail,
            )
        else ->
            stringResource(
                MR.string.chat_dice_summary_other_single,
                lines.senderName,
                lines.senderSum,
            )
    }
}

/** Format an OE chain's pair values for the sender line: "100+98+2" or "3−2−50". */
private fun formatOeDetail(pairs: List<Int>): String {
    if (pairs.isEmpty()) return ""
    val first = pairs.first()
    val sep = if (first <= 4) "−" else "+"
    return buildString {
        append(first)
        for (i in 1 until pairs.size) {
            append(sep)
            append(pairs[i])
        }
    }
}


@Composable
private fun battleResultText(result: BattleResult): String {
    val outright = result.leaders.singleOrNull()
    return when {
        outright != null && outright.isSelf ->
            stringResource(
                if (result.isClosed) MR.string.chat_dice_battle_winner_self
                else MR.string.chat_dice_battle_leader_self,
                result.maxSum,
            )
        outright != null ->
            stringResource(
                if (result.isClosed) MR.string.chat_dice_battle_winner_other
                else MR.string.chat_dice_battle_leader_other,
                outright.name,
                result.maxSum,
            )
        else -> {
            val and = stringResource(MR.string.chat_dice_battle_and)
            // NOTE: literal "You" is locale-incorrect (should be "Du" in
            // Danish). Pre-existing — the original `battleLeaderText` did the
            // same. Tracked separately from this fix.
            val displayNames = result.leaders.map { if (it.isSelf) "You" else it.name }
            val joined = joinNames(displayNames, and)
            val key = when {
                result.isClosed && result.isSelfAmong -> MR.string.chat_dice_battle_tie_winner_self
                result.isClosed -> MR.string.chat_dice_battle_tie_winner_other
                result.isSelfAmong -> MR.string.chat_dice_battle_tie_self
                else -> MR.string.chat_dice_battle_tie_other
            }
            stringResource(key, joined, result.maxSum)
        }
    }
}

private fun joinNames(names: List<String>, and: String): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    2 -> "${names[0]} $and ${names[1]}"
    else -> names.dropLast(1).joinToString(", ") + " $and ${names.last()}"
}
