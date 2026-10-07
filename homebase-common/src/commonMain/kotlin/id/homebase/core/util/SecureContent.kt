package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/** Blacks out captures of the hosting window while [active] (Android FLAG_SECURE); reference-counted per window. */
@Composable
expect fun SecureWindowEffect(active: Boolean)

/** True while the screen is being recorded or mirrored; [onScreenshot] fires on a user screenshot where the OS reports it (iOS). */
@Composable
expect fun rememberScreenCaptureObserver(onScreenshot: () -> Unit): State<Boolean>

class SecureFlagRefCounter {
    private var holds = 0
    private var flagWasPresent = false

    /** True when the caller must set the flag now. */
    fun acquire(flagAlreadySet: Boolean): Boolean {
        if (holds++ > 0) return false
        flagWasPresent = flagAlreadySet
        return !flagAlreadySet
    }

    /** True when the caller must clear the flag now. */
    fun release(): Boolean {
        if (holds == 0) return false
        return --holds == 0 && !flagWasPresent
    }

    val isHeld: Boolean get() = holds > 0
}
