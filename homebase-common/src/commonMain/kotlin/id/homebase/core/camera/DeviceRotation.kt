package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

// Buckets and dead bands derived from Signal-Android DeviceRotation.kt (AGPL-3.0, see NOTICE).
object DeviceRotation {
    const val SETTLE_MS = 200L

    /**
     * Maps a clockwise orientation reading (0..359) to a quarter turn. Readings in the 30° dead bands
     * between buckets keep [current], so hovering near a diagonal doesn't flicker.
     */
    fun quarterTurnFor(orientationDegrees: Int, current: QuarterTurn?): QuarterTurn? {
        val d = ((orientationDegrees % 360) + 360) % 360
        return when (d) {
            in 330..359, in 0..30 -> QuarterTurn.R0
            in 60..120 -> QuarterTurn.R90
            in 150..210 -> QuarterTurn.R180
            in 240..300 -> QuarterTurn.R270
            else -> current
        }
    }
}

@Composable
fun rememberDeviceRotation(): QuarterTurn = settledRotation(rememberRawDeviceRotation())

/** The latest physical reading, or null before the first one. */
@Composable
internal expect fun rememberRawDeviceRotation(): QuarterTurn?

/** The first reading lands at once; later ones only after holding still for [DeviceRotation.SETTLE_MS]. */
@Composable
internal fun settledRotation(raw: QuarterTurn?): QuarterTurn {
    var committed by remember { mutableStateOf<QuarterTurn?>(null) }
    LaunchedEffect(raw) {
        val next = raw ?: return@LaunchedEffect
        if (committed != null) delay(DeviceRotation.SETTLE_MS)
        committed = next
    }
    return committed ?: QuarterTurn.R0
}
