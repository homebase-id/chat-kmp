package id.homebase.imageeditor.core

/**
 * Aspect-ratio constraints exposed to the cropper UI.
 *
 * [FREE] — user can resize the crop rect on each axis independently.
 * [SQUARE] — width and height are locked equal (1:1).
 * [R_4_3] — landscape 4:3 (or portrait 3:4 once rotated).
 * [R_16_9] — landscape 16:9.
 * [LOCKED] — caller-supplied fixed ratio; [ratio] is width/height.
 */
sealed class AspectMode {
    abstract val ratio: Float?

    data object Free : AspectMode() { override val ratio: Float? = null }
    data object Square : AspectMode() { override val ratio: Float = 1f }
    data object R_4_3 : AspectMode() { override val ratio: Float = 4f / 3f }
    data object R_16_9 : AspectMode() { override val ratio: Float = 16f / 9f }
    data class Custom(override val ratio: Float) : AspectMode()

    val isLocked: Boolean get() = ratio != null

    companion object {
        val Default: AspectMode = Free
        val ChipChoices: List<AspectMode> = listOf(Free, Square, R_4_3, R_16_9)
    }
}
