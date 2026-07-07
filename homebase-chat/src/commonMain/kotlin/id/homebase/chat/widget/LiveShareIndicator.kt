package id.homebase.chat.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import id.homebase.core.location.LIVE_SHARE_INDEFINITE
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.location_sharing_indicator_cd
import kotlinx.coroutines.delay
import kotlin.time.Clock
import org.jetbrains.compose.resources.stringResource

/**
 * The purple "you're sharing your live location" pin for the chat top bars (#816). Renders an
 * [IconButton] while `now < untilMs`, nothing otherwise — including flipping itself off at the
 * exact moment the share expires (the relay roster emits no event at expiry, so the deadline is
 * watched here with a ticker, same pattern as the location bubble's countdown). An indefinite
 * share ([LIVE_SHARE_INDEFINITE]) has no deadline to watch — the ticker is skipped and the pin
 * stays lit until an explicit stop swaps [untilMs]. Tap opens the location dashboard, where
 * every outgoing share is listed with stop controls.
 *
 * Filled glyph deliberately (the outlined pin is the passive Location nav icon; filled signals
 * an ACTIVE broadcast), tinted with the theme's `liveSharing` token — never a hardcoded color.
 */
@Composable
fun LiveShareIndicator(
    untilMs: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (untilMs == null) return
    // Coarse ticker (≤30 s steps) that lands exactly on the deadline, then stops.
    var nowMs by remember(untilMs) { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(untilMs) {
        if (untilMs == LIVE_SHARE_INDEFINITE) return@LaunchedEffect
        while (true) {
            val now = Clock.System.now().toEpochMilliseconds()
            nowMs = now
            if (now >= untilMs) break
            delay(minOf(untilMs - now, 30_000L) + 50)
        }
    }
    if (nowMs >= untilMs) return

    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = stringResource(MR.string.location_sharing_indicator_cd),
            tint = HomebaseTheme.extendedColors.liveSharing,
        )
    }
}
