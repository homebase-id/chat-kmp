package id.homebase.core.camera

import androidx.compose.runtime.Composable

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
expect fun rememberDeviceRotation(): QuarterTurn
