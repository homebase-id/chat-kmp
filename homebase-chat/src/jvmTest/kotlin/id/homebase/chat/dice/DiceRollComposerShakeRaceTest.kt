package id.homebase.chat.dice

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Reproduces the 1d4-shake crash from build 1.3.1420.
 *
 * The harness composable below mirrors the dice composer's wiring exactly:
 *  - a `count` state derived from a mode toggle (OE → 2, Standard → 1),
 *  - a `displayValues` `SnapshotStateList` resized by `LaunchedEffect(count)`,
 *  - a `doRoll` lambda recreated each composition that loops `0 until count`
 *    and writes into `displayValues`,
 *  - a `LaunchedEffect(shakeDetector.isAvailable)` that collects shake events
 *    forever and invokes `doRoll`.
 *
 * Without `rememberUpdatedState(doRoll)`, the long-running collect captures
 * the first composition's `doRoll` (count = 2). After the user toggles OE
 * off, `displayValues` shrinks to size 1 but the captured `doRoll` still
 * thinks count is 2, so the next shake writes `displayValues[1]` and crashes
 * with `IndexOutOfBoundsException: index 1, size 1`. The test asserts no
 * such crash propagates.
 */
@OptIn(ExperimentalTestApi::class)
class DiceRollComposerShakeRaceTest {

    @Test
    fun shakeAfterModeToggle_doesNotCrash() = runComposeUiTest {
        val fakeShake = FakeShakeDetector()
        var caught: Throwable? = null

        setContent {
            MaterialTheme {
                ComposerShakeHarness(
                    shakeDetector = fakeShake,
                    onError = { caught = it },
                )
            }
        }

        // Settle the initial composition (mode = OE → count = 2,
        // displayValues size 2).
        waitForIdle()

        // Toggle OE off → mode = Standard, count = 1. The
        // LaunchedEffect(count) shrinks displayValues to size 1.
        onNodeWithTag(DICE_OE_TOGGLE_TAG).performClick()
        waitForIdle()

        // Fire a shake. With the bug, this invokes the stale doRoll (count = 2)
        // and writes displayValues[1] on a size-1 list → IOOB on the main
        // coroutine, captured into `caught` by the harness CoroutineExceptionHandler.
        fakeShake.fire()
        waitForIdle()
        // Allow the launched tumble coroutine to schedule and any thrown
        // exception to propagate.
        mainClock.advanceTimeBy(500L)
        waitForIdle()

        assertNull(caught, "shake after toggle must not crash, but got: $caught")
    }
}

@Composable
private fun ComposerShakeHarness(
    shakeDetector: ShakeDetector,
    onError: (Throwable) -> Unit,
) {
    var mode by remember { mutableStateOf(HarnessMode.OpenEnded) }
    val count = if (mode == HarnessMode.OpenEnded) 2 else 1

    val displayValues: SnapshotStateList<Int?> = remember {
        mutableStateListOf<Int?>(null, null)
    }
    LaunchedEffect(count) {
        while (displayValues.size < count) displayValues.add(null)
        while (displayValues.size > count) displayValues.removeAt(displayValues.lastIndex)
    }

    val scope = rememberCoroutineScope()
    // Mirrors the production doRoll: captures `count` per composition and
    // launches a coroutine that walks `0 until count`. Intentionally does
    // NOT use the clamped `runTumble` helper here — this test isolates the
    // `rememberUpdatedState(doRoll)` fix in step 1, separate from the
    // belt-and-suspenders clamp in step 2 covered by `DiceRollTumbleTest`.
    val doRoll: () -> Unit = {
        scope.launch {
            try {
                for (frame in 0 until 3) {
                    for (i in 0 until count) {
                        displayValues[i] = 2
                    }
                    delay(10L)
                }
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    // The fix: rememberUpdatedState re-binds the captured callback so the
    // long-running collect always invokes the current `doRoll` with the
    // current `count`. Removing this `rememberUpdatedState` and replacing
    // `currentDoRoll()` with `doRoll()` below reproduces the production
    // crash.
    val currentDoRoll by rememberUpdatedState(doRoll)
    LaunchedEffect(shakeDetector.isAvailable) {
        if (!shakeDetector.isAvailable) return@LaunchedEffect
        shakeDetector.events().collect {
            currentDoRoll()
        }
    }

    Switch(
        checked = mode == HarnessMode.OpenEnded,
        onCheckedChange = { on ->
            mode = if (on) HarnessMode.OpenEnded else HarnessMode.Standard
        },
        modifier = Modifier.testTag(DICE_OE_TOGGLE_TAG),
    )
    Text("count=$count size=${displayValues.size}")
}

private enum class HarnessMode { OpenEnded, Standard }

private class FakeShakeDetector : ShakeDetector {
    override val isAvailable: Boolean = true
    private val flow = MutableSharedFlow<ShakeEvent>(extraBufferCapacity = 16)
    override fun events(): Flow<ShakeEvent> = flow
    fun fire() {
        check(flow.tryEmit(ShakeEvent(intensity = 1.7f, accelSamples = listOf(1L))))
    }
}
