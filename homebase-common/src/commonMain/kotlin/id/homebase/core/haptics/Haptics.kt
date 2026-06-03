package id.homebase.core.haptics

/**
 * Semantic haptic events. Call sites speak intent (e.g. [Confirm]) rather than
 * Compose's raw HapticFeedbackType. The mapping to platform haptics lives in
 * ComposeHaptics; this enum stays free of any Compose dependency so the gating
 * logic in [GatedHaptics] is plain-Kotlin testable.
 */
enum class HapticEvent {
    /** Light incremental tick — e.g. a rotation-dial scrub crossing a degree. */
    Selection,

    /** Firm press / threshold cross — swipe threshold, press-and-hold, long-press. */
    LongPress,

    /** Positive action completed — message sent, reaction added. */
    Confirm,
}

/** Plays a [HapticEvent]. Obtain one inside a composable via `rememberHaptics()`. */
interface Haptics {
    fun perform(event: HapticEvent)
}

/**
 * Wraps another [Haptics] and only forwards events while [enabled] returns true.
 * This is what makes every call site respect the user's "Haptic feedback"
 * setting with no per-site code. Pure Kotlin — no Compose — so it is unit tested
 * directly with a fake delegate. [enabled] is re-read on every [perform] so the
 * toggle takes effect immediately.
 */
class GatedHaptics(
    private val delegate: Haptics,
    private val enabled: () -> Boolean,
) : Haptics {
    override fun perform(event: HapticEvent) {
        if (enabled()) delegate.perform(event)
    }
}
