package id.homebase.chat.dice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.haptics.rememberHaptics
import id.homebase.resources.MR
import id.homebase.resources.chat_dice_battle_target
import id.homebase.resources.chat_dice_battle_title
import id.homebase.resources.chat_dice_roll
import id.homebase.resources.chat_dice_rolling
import id.homebase.resources.chat_dice_shake_hint
import id.homebase.resources.menu_back
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Fullscreen "roll to beat them" sheet. Locks dice config to the chain's newest
 * member (so a long-press on an older bubble still battles the latest roll).
 * Bakes the leaders snapshot into the new descriptor at send so receiving
 * bubbles render self-contained.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BattleRollSheet(
    parentMessage: MessageUiModel,
    chainDescriptors: ImmutableList<DiceRollDescriptor>,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BattleRollSheetContent(
            parentMessage = parentMessage,
            chainDescriptors = chainDescriptors,
            onDismiss = onDismiss,
            onSent = onSent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BattleRollSheetContent(
    parentMessage: MessageUiModel,
    chainDescriptors: ImmutableList<DiceRollDescriptor>,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    val sender: ChatMessageSenderService = koinInject()
    val ownerSession: OwnerSessionRepository = koinInject()
    val shakeDetector: ShakeDetector = koinInject()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    val parentDescriptor = (parentMessage.messageContent as? MessageContent.DiceRoll)?.descriptor
    if (parentDescriptor == null || !parentDescriptor.isValid()) {
        // Defensive — caller should have gated this. Auto-dismiss if we ever land
        // here so the sheet doesn't show empty UI on a malformed parent.
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    // Lock to the chain's newest member, not necessarily what was long-pressed.
    val newest = remember(parentDescriptor.chainRootMessageId(), chainDescriptors) {
        findChainNewest(parentDescriptor, chainDescriptors)
    }
    val faces = newest.faces
    val mode = newest.mode
    // In OE mode the challenger always starts from 2 d10 placeholders — their
    // chain may extend independently of the parent's.
    val initialCount = if (mode == DiceRollMode.OpenEndedD100) 2 else newest.latest.results.size

    val ownerOdinId = ownerSession.user.collectAsStateWithLifecycle().value?.odinId

    val displayValues: SnapshotStateList<Int?> = remember(initialCount) {
        List<Int?>(initialCount) { null }.toMutableStateList()
    }

    var rolling by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val isBusy by remember { derivedStateOf { rolling || sending } }

    var shakeSamples by remember { mutableStateOf<List<Long>?>(null) }
    var shakeTriggered by remember { mutableStateOf(false) }

    val doRoll: () -> Unit = roll@{
        if (isBusy) return@roll
        if (ownerOdinId == null) return@roll
        haptics.perform(HapticEvent.LongPress)
        rolling = true
        val seed = if (shakeTriggered) shakeSamples else null
        val finalResults = if (mode == DiceRollMode.OpenEndedD100) {
            rollOpenEndedD100(seed)
        } else {
            roll(count = initialCount, faces = faces, shakeSamples = seed)
        }
        scope.launch {
            runTumble(
                frames = TUMBLE_FRAMES,
                count = initialCount,
                faces = faces,
                displayValues = displayValues,
                frameDelayMs = TUMBLE_FRAME_MS,
            )
            while (displayValues.size < finalResults.size) displayValues.add(null)
            for (i in finalResults.indices) displayValues[i] = finalResults[i]
            haptics.perform(HapticEvent.LongPress)
            rolling = false
            sending = true

            val messageId = Uuid.random()
            val nowMs = Clock.System.now().toEpochMilliseconds()
            // Guarantee strict-monotonic timestamps so isValid()'s chronology
            // check passes even on devices with coarse clocks.
            val rolledAtUtcMs = maxOf(nowMs, newest.latest.rolledAtUtcMs + 1)
            val newEntry = ChainRoll(
                messageId = messageId,
                odinId = ownerOdinId,
                results = finalResults,
                rolledAtUtcMs = rolledAtUtcMs,
                source = if (seed != null) RollSource.ShakeSeeded else RollSource.LocalRandom,
            )
            val descriptor = DiceRollDescriptor(
                faces = faces,
                mode = mode,
                rolls = newest.rolls + newEntry,
            )
            runCatching {
                sender.sendNewTypedMessage(
                    messageUniqueId = messageId,
                    conversationId = parentMessage.conversationId,
                    content = MessageContent.DiceRoll(descriptor),
                    previousMessageUniqueId = null,
                )
            }
            sending = false
            shakeTriggered = false
            shakeSamples = null
            onSent()
        }
    }

    // See note in DiceRollComposerSheet — without rememberUpdatedState, the
    // long-running collect captures the first composition's `doRoll`, whose
    // `initialCount` snapshot can disagree with `displayValues.size` after a
    // chainDescriptors update re-keys `displayValues`.
    val currentDoRoll by rememberUpdatedState(doRoll)
    LaunchedEffect(shakeDetector.isAvailable) {
        if (!shakeDetector.isAvailable) return@LaunchedEffect
        shakeDetector.events().collect { event ->
            shakeSamples = event.accelSamples
            shakeTriggered = true
            currentDoRoll()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.chat_dice_battle_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Header — beat the chain's current leader. Read directly from
            // newest's embedded history. For OE, leader uses scored (signed)
            // sums, so a -49 doesn't beat a +12.
            val leaderSum = newest.rolls.maxOf { newest.scoredSumOf(it) }
            val leaderName = newest.rolls
                .firstOrNull { newest.scoredSumOf(it) == leaderSum }
                ?.odinId?.domainName
                ?.substringBefore('.')
                ?.ifBlank { null }
                ?: newest.latest.odinId.domainName
            Text(
                text = stringResource(
                    MR.string.chat_dice_battle_target,
                    leaderSum,
                    leaderName,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            // Show opponent's faces so it's visually clear what dice we're battling.
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                DicePreviewArea(
                    mode = mode,
                    faces = newest.faces,
                    values = newest.latest.results,
                    cellSize = 40.dp,
                )
            }

            // Our roll preview row (placeholders until rolled).
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                DicePreviewArea(
                    mode = mode,
                    faces = faces,
                    values = displayValues,
                )
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = doRoll,
                enabled = !isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                contentPadding = PaddingValues(vertical = 14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (rolling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(MR.string.chat_dice_rolling))
                } else {
                    Icon(
                        imageVector = Icons.Filled.Casino,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(MR.string.chat_dice_roll))
                }
            }

            if (shakeDetector.isAvailable) {
                Text(
                    text = stringResource(MR.string.chat_dice_shake_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private const val TUMBLE_FRAMES = 6
private const val TUMBLE_FRAME_MS = 80L
