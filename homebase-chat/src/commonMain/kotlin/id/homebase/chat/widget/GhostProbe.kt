package id.homebase.chat.widget

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import co.touchlab.kermit.Logger
import id.homebase.chat.conversationlist.PendingOutgoingMessage
import id.homebase.chat.conversationlist.messageListKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// Temporary ghost-bubble instrumentation; set from the app's DiagnosticsCrashTrigger.enabled (dev/debug builds only).
object GhostProbe {
    var enabled = false
}

private const val TAG = "GhostProbe"

private class Drop(val at: TimeMark, val frame: Long)

private class ProbeState {
    var previous: List<Any>? = null
    var frame = 0L
    val drops = HashMap<String, Drop>()
}

@Composable
internal fun GhostProbeEffect(conversationKey: Any, items: List<Any>, listState: LazyListState) {
    if (!GhostProbe.enabled) return
    val state = remember(conversationKey) { ProbeState() }
    val scope = rememberCoroutineScope()
    val currentItems by rememberUpdatedState(items)
    var tracking by remember { mutableStateOf(false) }

    // Counts frames only while a placeholder's key is missing, so an idle list requests no frames.
    LaunchedEffect(tracking) {
        if (!tracking) return@LaunchedEffect
        while (state.drops.isNotEmpty()) {
            withFrameNanos { state.frame++ }
            state.drops.entries.removeAll { (key, drop) ->
                (drop.at.elapsedNow().inWholeSeconds >= 10).also { expired ->
                    if (expired) Logger.w(TAG) { "key=$key real message not in list 10s after placeholder drop" }
                }
            }
        }
        tracking = false
    }

    SideEffect {
        val previous = state.previous
        if (previous === items) return@SideEffect
        state.previous = items
        if (previous == null) return@SideEffect
        val pendingBefore = previous.filterIsInstance<PendingOutgoingMessage>().mapTo(HashSet(), ::messageListKey)
        val pendingNow = items.filterIsInstance<PendingOutgoingMessage>().mapTo(HashSet(), ::messageListKey)
        val keysNow = items.mapTo(HashSet(), ::messageListKey)

        val landed = mutableListOf<String>()
        for (key in pendingBefore - pendingNow) {
            if (key in keysNow) {
                Logger.i(TAG) { "key=$key handoff same composition gapMs=0 gapFrames=0" }
                landed += key
            } else {
                state.drops[key] = Drop(TimeSource.Monotonic.markNow(), state.frame)
                Logger.i(TAG) { "key=$key placeholder dropped, real message not in list yet" }
            }
        }
        state.drops.entries.removeAll { (key, drop) ->
            (key in keysNow).also { returned ->
                if (returned) {
                    Logger.w(TAG) {
                        "key=$key real message landed after gap gapMs=${drop.at.elapsedNow().inWholeMilliseconds} " +
                            "gapFrames=${state.frame - drop.frame}"
                    }
                    landed += key
                }
            }
        }
        tracking = state.drops.isNotEmpty()

        for (key in landed) scope.launch {
            delay(2_000)
            val listKeys = currentItems.mapTo(HashSet(), ::messageListKey)
            val visible = listState.layoutInfo.visibleItemsInfo
            val orphaned = visible.count { it.key !in listKeys }
            Logger.i(TAG) { "key=$key +2s visibleItems=${visible.size} laidOutKeysNotInList=$orphaned" }
        }
    }
}
