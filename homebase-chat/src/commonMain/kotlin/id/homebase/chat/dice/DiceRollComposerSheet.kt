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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
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
import id.homebase.resources.chat_dice_decrement
import id.homebase.resources.chat_dice_faces_label
import id.homebase.resources.chat_dice_increment
import id.homebase.resources.chat_dice_max_dice_warning
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

    var faces by remember { mutableIntStateOf(coerceFaces(initialFaces)) }
    var count by remember { mutableIntStateOf(coerceCount(initialCount)) }

    // Faces visible in the preview row. While rolling, this updates rapidly.
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
        val finalResults = roll(count = count, faces = faces, shakeSamples = seed)
        scope.launch {
            // Tumble animation: cycle through random faces, then settle.
            val frames = TUMBLE_FRAMES
            for (frame in 0 until frames) {
                for (i in 0 until count) {
                    displayValues[i] = Random.nextInt(1, faces + 1)
                }
                delay(TUMBLE_FRAME_MS)
            }
            for (i in 0 until count) displayValues[i] = finalResults[i]
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            rolling = false
            sending = true

            preferences.setLast(count, faces)
            val authorOdinId = ownerSession.user.value?.odinId?.domainName ?: ""
            val descriptor = DiceRollDescriptor(
                rollId = Uuid.random().toString(),
                faces = faces,
                results = finalResults,
                rolledByOdinId = authorOdinId,
                rolledAtUtcMs = Clock.System.now().toEpochMilliseconds(),
                source = if (seed != null) RollSource.ShakeSeeded else RollSource.LocalRandom,
            )
            runCatching {
                sender.sendNewTypedMessage(
                    messageUniqueId = Uuid.random(),
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
    LaunchedEffect(shakeDetector.isAvailable) {
        if (!shakeDetector.isAvailable) return@LaunchedEffect
        shakeDetector.events().collect { event ->
            shakeSamples = event.accelSamples
            shakeTriggered = true
            doRoll()
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
            FacesSelector(
                selectedFaces = faces,
                onFacesChange = { if (!isBusy) faces = it },
                enabled = !isBusy,
            )

            CountStepper(
                count = count,
                onCountChange = { if (!isBusy) count = it },
                enabled = !isBusy,
            )

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (i in 0 until count) {
                        DiceFaceImage(
                            faces = faces,
                            value = displayValues.getOrNull(i),
                            modifier = Modifier.size(56.dp),
                        )
                    }
                }
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
                    label = { Text("d$option", fontWeight = FontWeight.SemiBold) },
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
    val atMax = count >= DiceRollDescriptor.MAX_DICE
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
                text = "${count}d",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            IconButton(
                onClick = {
                    onCountChange((count + 1).coerceAtMost(DiceRollDescriptor.MAX_DICE))
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
                    DiceRollDescriptor.MAX_DICE,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val TUMBLE_FRAMES = 6
private const val TUMBLE_FRAME_MS = 80L

private fun coerceFaces(value: Int): Int =
    if (value in DiceRollDescriptor.ALLOWED_FACES) value else DiceRollPreferences.DEFAULT_FACES

private fun coerceCount(value: Int): Int =
    value.coerceIn(1, DiceRollDescriptor.MAX_DICE)

/**
 * Pure roll function — extracted so it's unit-testable. When [shakeSamples] is
 * non-empty we XOR the samples into the seed for an extra dose of fun-grade
 * entropy; otherwise we fall back to [Random.Default].
 */
internal fun roll(count: Int, faces: Int, shakeSamples: List<Long>?): List<Int> {
    require(count >= 1) { "count must be >= 1" }
    require(faces in DiceRollDescriptor.ALLOWED_FACES) { "faces $faces not allowed" }
    val rng = if (!shakeSamples.isNullOrEmpty()) {
        var seed = Clock.System.now().toEpochMilliseconds()
        for (sample in shakeSamples) seed = seed xor sample
        Random(seed)
    } else {
        Random.Default
    }
    return List(count) { rng.nextInt(1, faces + 1) }
}
