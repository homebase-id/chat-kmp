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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.content.MessageContent
import id.homebase.resources.MR
import id.homebase.resources.chat_dice_composer_title
import id.homebase.resources.chat_dice_count_label
import id.homebase.resources.chat_dice_count_value
import id.homebase.resources.chat_dice_decrement
import id.homebase.resources.chat_dice_face_label
import id.homebase.resources.chat_dice_faces_label
import id.homebase.resources.chat_dice_increment
import id.homebase.resources.chat_dice_max_dice_warning
import id.homebase.resources.chat_dice_mode_oe_hint
import id.homebase.resources.chat_dice_mode_oe_label
import id.homebase.resources.chat_dice_roll
import id.homebase.resources.chat_dice_rolling
import id.homebase.resources.chat_dice_shake_hint
import id.homebase.resources.menu_back
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Fullscreen composer for a dice roll. Mirrors `EventComposerSheet`'s shape:
 * `Dialog` → `Scaffold` → form → primary action. State is local — the composer
 * is short-lived. Last-used `count`/`faces` are seeded from
 * [DiceRollPreferences] and written back on send.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiceRollComposerSheet(
    conversationId: Uuid,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DiceRollComposerContent(
            conversationId = conversationId,
            onDismiss = onDismiss,
            onSent = onSent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DiceRollComposerContent(
    conversationId: Uuid,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    val sender: ChatMessageSenderService = koinInject()
    val ownerSession: OwnerSessionRepository = koinInject()
    val preferences: DiceRollPreferences = koinInject()
    val shakeDetector: ShakeDetector = koinInject()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val initialFaces by preferences.lastFaces.collectAsStateWithLifecycle()
    val initialCount by preferences.lastCount.collectAsStateWithLifecycle()
    val initialMode by preferences.lastMode.collectAsStateWithLifecycle()

    var mode by remember { mutableStateOf(initialMode) }
    // OE mode locks dice to 2d10 in the preview / on the wire. Standard mode
    // keeps the user's last-used count/faces.
    var standardFaces by remember { mutableIntStateOf(coerceFaces(initialFaces)) }
    var standardCount by remember { mutableIntStateOf(coerceCount(initialCount)) }
    val faces = if (mode == DiceRollMode.OpenEndedD100) 10 else standardFaces
    val count = if (mode == DiceRollMode.OpenEndedD100) 2 else standardCount

    // Faces visible in the preview row. While rolling, this updates rapidly.
    // In OE mode the list grows after the roll completes if a chain extended
    // past the initial pair.
    val displayValues: SnapshotStateList<Int?> = remember {
        List<Int?>(count) { null }.toMutableStateList()
    }
    LaunchedEffect(count, faces) {
        // Resize the preview list to match `count` and clear any settled values
        // so the user sees the right number of placeholder dice.
        while (displayValues.size < count) displayValues.add(null)
        while (displayValues.size > count) displayValues.removeAt(displayValues.lastIndex)
    }

    var rolling by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val isBusy by remember { derivedStateOf { rolling || sending } }

    // Hold latest shake samples so the next roll can mix them into the seed.
    var shakeSamples by remember { mutableStateOf<List<Long>?>(null) }
    var shakeTriggered by remember { mutableStateOf(false) }

    val doRoll: () -> Unit = roll@{
        if (isBusy) return@roll
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        rolling = true
        val seed = if (shakeTriggered) shakeSamples else null
        val finalResults = if (mode == DiceRollMode.OpenEndedD100) {
            rollOpenEndedD100(seed)
        } else {
            roll(count = count, faces = faces, shakeSamples = seed)
        }
        scope.launch {
            // Tumble animation: cycle through random faces, then settle. For
            // OE we tumble the initial 2-die pair only; the trailing chain (if
            // any) pops in when rolling completes.
            runTumble(
                frames = TUMBLE_FRAMES,
                count = count,
                faces = faces,
                displayValues = displayValues,
                frameDelayMs = TUMBLE_FRAME_MS,
            )
            // Grow the preview list to fit the full OE chain before stamping
            // in the final faces.
            while (displayValues.size < finalResults.size) displayValues.add(null)
            for (i in finalResults.indices) displayValues[i] = finalResults[i]
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            rolling = false
            sending = true

            preferences.setLast(standardCount, standardFaces, mode)
            val authorOdinId = ownerSession.user.value?.odinId
            if (authorOdinId == null) {
                sending = false
                shakeTriggered = false
                shakeSamples = null
                onSent()
                return@launch
            }
            val messageId = Uuid.random()
            val descriptor = DiceRollDescriptor(
                faces = faces,
                mode = mode,
                rolls = listOf(
                    ChainRoll(
                        messageId = messageId,
                        odinId = authorOdinId,
                        results = finalResults,
                        rolledAtUtcMs = Clock.System.now().toEpochMilliseconds(),
                        source = if (seed != null) RollSource.ShakeSeeded else RollSource.LocalRandom,
                    ),
                ),
            )
            runCatching {
                sender.sendNewTypedMessage(
                    messageUniqueId = messageId,
                    conversationId = conversationId,
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

    // Subscribe to shake events while the composer is open (when available).
    // The collect lambda lives for the lifetime of this composition, so it
    // would otherwise capture the *first* composition's `doRoll` — whose
    // `count` snapshot can disagree with `displayValues.size` once the user
    // toggles OE / changes the count. `rememberUpdatedState` re-binds on
    // every recomposition so a shake always invokes the current `doRoll`.
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
                title = { Text(stringResource(MR.string.chat_dice_composer_title)) },
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
            OpenEndedToggle(
                checked = mode == DiceRollMode.OpenEndedD100,
                onCheckedChange = { on ->
                    if (!isBusy) {
                        mode = if (on) DiceRollMode.OpenEndedD100 else DiceRollMode.Standard
                    }
                },
                enabled = !isBusy,
            )

            FacesSelector(
                selectedFaces = faces,
                onFacesChange = { if (!isBusy && mode == DiceRollMode.Standard) standardFaces = it },
                enabled = !isBusy && mode == DiceRollMode.Standard,
            )

            CountStepper(
                count = count,
                onCountChange = { if (!isBusy && mode == DiceRollMode.Standard) standardCount = it },
                enabled = !isBusy && mode == DiceRollMode.Standard,
            )

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacesSelector(
    selectedFaces: Int,
    onFacesChange: (Int) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(MR.string.chat_dice_faces_label),
            style = MaterialTheme.typography.labelLarge,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (option in DiceRollDescriptor.ALLOWED_FACES) {
                FilterChip(
                    selected = option == selectedFaces,
                    enabled = enabled,
                    onClick = { onFacesChange(option) },
                    label = {
                        Text(
                            text = stringResource(MR.string.chat_dice_face_label, option),
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }
    }
}

@Composable
private fun CountStepper(
    count: Int,
    onCountChange: (Int) -> Unit,
    enabled: Boolean,
) {
    val atMax = count >= DiceRollDescriptor.MAX_STANDARD_DICE
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(MR.string.chat_dice_count_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onCountChange((count - 1).coerceAtLeast(1)) },
                enabled = enabled && count > 1,
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = stringResource(MR.string.chat_dice_decrement),
                )
            }
            Text(
                text = stringResource(MR.string.chat_dice_count_value, count),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            IconButton(
                onClick = {
                    onCountChange((count + 1).coerceAtMost(DiceRollDescriptor.MAX_STANDARD_DICE))
                },
                enabled = enabled && !atMax,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(MR.string.chat_dice_increment),
                )
            }
        }
        if (atMax) {
            Text(
                text = stringResource(
                    MR.string.chat_dice_max_dice_warning,
                    DiceRollDescriptor.MAX_STANDARD_DICE,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shared preview helper used by both the dice composer and the battle sheet.
 * In Standard mode shows a FlowRow of all dice; in OE mode pairs them up
 * (tens-die first, ones-die second) so the user sees the percentile reading
 * the way physical dice would land.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DicePreviewArea(
    mode: DiceRollMode,
    faces: Int,
    values: List<Int?>,
    cellSize: Dp = 56.dp,
) {
    if (mode == DiceRollMode.OpenEndedD100) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            var i = 0
            while (i < values.size - 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PercentileTensFaceImage(
                        value = values[i],
                        modifier = Modifier.size(cellSize),
                    )
                    DiceFaceImage(
                        faces = 10,
                        value = values[i + 1],
                        modifier = Modifier.size(cellSize),
                    )
                }
                i += 2
            }
        }
    } else {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (v in values) {
                DiceFaceImage(
                    faces = faces,
                    value = v,
                    modifier = Modifier.size(cellSize),
                )
            }
        }
    }
}

@Composable
private fun OpenEndedToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(MR.string.chat_dice_mode_oe_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.testTag(DICE_OE_TOGGLE_TAG),
            )
        }
        if (checked) {
            Text(
                text = stringResource(MR.string.chat_dice_mode_oe_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal const val DICE_OE_TOGGLE_TAG = "dice_oe_toggle"

private const val TUMBLE_FRAMES = 6
private const val TUMBLE_FRAME_MS = 80L

/**
 * Tumble animation inner loop, extracted so it can be unit-tested. The upper
 * bound is clamped against `displayValues.size` because the list can be
 * resized mid-flight by `LaunchedEffect(count, faces)` if the user toggles
 * mode while a stale `doRoll` (captured pre-toggle) is still running — see
 * the `rememberUpdatedState(doRoll)` note above.
 */
internal suspend fun runTumble(
    frames: Int,
    count: Int,
    faces: Int,
    displayValues: SnapshotStateList<Int?>,
    frameDelayMs: Long,
    randomFace: (Int) -> Int = { faceCount -> Random.nextInt(1, faceCount + 1) },
) {
    for (frame in 0 until frames) {
        val limit = minOf(count, displayValues.size)
        for (i in 0 until limit) {
            displayValues[i] = randomFace(faces)
        }
        delay(frameDelayMs)
    }
}

private fun coerceFaces(value: Int): Int =
    if (value in DiceRollDescriptor.ALLOWED_FACES) value else DiceRollPreferences.DEFAULT_FACES

private fun coerceCount(value: Int): Int =
    value.coerceIn(1, DiceRollDescriptor.MAX_STANDARD_DICE)

