package id.homebase.imageeditor.ui.widget

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import id.homebase.imageeditor.core.Bounds
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.RectF
import id.homebase.imageeditor.ui.MatrixSnapshot

private const val ANIMATION_MS = 250

private val DecelerateEasing = Easing { t -> 1f - (1f - t) * (1f - t) }

/** [hit] is what gestures hit-test against; [draw] is what is painted. */
class AnimatedSnapshots(val hit: MatrixSnapshot, val draw: MatrixSnapshot)

internal fun MatrixSnapshot.imageChain(): Matrix2D =
    Matrix2D(flipRotate).also {
        it.preConcat(mainImageLocal)
        it.preConcat(mainImageEditor)
    }

internal fun animationUndo(from: Matrix2D, to: Matrix2D): Matrix2D? {
    val undo = Matrix2D()
    if (!to.invert(undo)) return null
    undo.preConcat(from)
    return if (undo.isIdentity()) null else undo
}

internal fun lerpToIdentity(undo: Matrix2D, fraction: Float): Matrix2D {
    val out = Matrix2D()
    for (i in 0 until 6) {
        out.values[i] = undo.values[i] * fraction + out.values[i] * (1f - fraction)
    }
    return out
}

/**
 * Signal's `AnimationMatrix`: a discrete edit is drawn as `target * lerp(to^-1 * from, I, 1 - progress)`
 * so it starts on the old picture and ends on the target. Interpolation is element-wise, so the
 * in-between matrix need not be invertible; hit-testing never sees it.
 */
internal class DiscreteChangeAnimator {
    private var generation: Int? = null
    private var previous: MatrixSnapshot? = null
    private var imageUndo: Matrix2D? = null
    private var cropUndo: Matrix2D? = null
    private var awaitingStart = false

    fun draw(target: MatrixSnapshot, liveFraction: Float): MatrixSnapshot {
        val prev = previous
        if (generation != null && generation != target.animGeneration && prev != null) {
            val f = if (awaitingStart) 1f else liveFraction
            val fromImage = prev.imageChain().also {
                it.preConcat(lerpToIdentity(imageUndo ?: Matrix2D(), f))
            }
            val fromCrop = Matrix2D(prev.cropFrameMatrix).also {
                it.preConcat(lerpToIdentity(cropUndo ?: Matrix2D(), f))
            }
            imageUndo = animationUndo(fromImage, target.imageChain())
            cropUndo = animationUndo(fromCrop, target.cropFrameMatrix)
            awaitingStart = imageUndo != null || cropUndo != null
        }
        generation = target.animGeneration
        previous = target
        return apply(target, if (awaitingStart) 1f else liveFraction)
    }

    fun markStarted() {
        awaitingStart = false
    }

    private fun apply(target: MatrixSnapshot, fraction: Float): MatrixSnapshot {
        val image = imageUndo
        val crop = cropUndo
        if (fraction <= 0f || (image == null && crop == null)) return target
        val editor = image?.let {
            Matrix2D(target.mainImageEditor).also { m -> m.preConcat(lerpToIdentity(it, fraction)) }
        } ?: target.mainImageEditor
        val frame = crop?.let {
            Matrix2D(target.cropFrameMatrix).also { m -> m.preConcat(lerpToIdentity(it, fraction)) }
        } ?: target.cropFrameMatrix
        val rect = RectF()
        frame.mapRect(rect, Bounds.fullBounds())
        return target.copy(mainImageEditor = editor, cropFrameMatrix = frame, cropRect = rect)
    }
}

/**
 * Returns a [MatrixSnapshot] whose [MatrixSnapshot.viewLocal] is smoothly
 * animated toward the target snapshot's value, instead of jumping
 * instantaneously. All other fields pass through unchanged so gesture-rate
 * matrices (mainImage editor / cropEditorElement editor) stay live.
 *
 * `view.localMatrix` is always a uniform scale + translate (it is set via
 * `setRectToRect(..., CENTER)`), so animating its three components — uniform
 * scale, translateX, translateY — captures all of it. Rotation/skew on the
 * view layer would not animate correctly with this; if that ever changes,
 * decompose differently.
 *
 * Discrete edits (see [MatrixSnapshot.animGeneration]) are additionally
 * animated in [AnimatedSnapshots.draw] only, over 250 ms with a decelerate
 * curve like Signal's `AnimationMatrix`. Hit-testing keeps the target matrices.
 */
@Composable
fun rememberAnimatedSnapshot(target: MatrixSnapshot): AnimatedSnapshots {
    val v = target.viewLocal.values
    val targetScale = v[Matrix2D.MSCALE_X]
    val targetTx = v[Matrix2D.MTRANS_X]
    val targetTy = v[Matrix2D.MTRANS_Y]
    val anim = tween<Float>(durationMillis = 250)

    val scale by animateFloatAsState(targetScale, animationSpec = anim, label = "viewScale")
    val tx by animateFloatAsState(targetTx, animationSpec = anim, label = "viewTx")
    val ty by animateFloatAsState(targetTy, animationSpec = anim, label = "viewTy")

    val animated = Matrix2D()
    animated.values[Matrix2D.MSCALE_X] = scale
    animated.values[Matrix2D.MSCALE_Y] = scale
    animated.values[Matrix2D.MTRANS_X] = tx
    animated.values[Matrix2D.MTRANS_Y] = ty

    val hit = target.copy(viewLocal = animated)

    val animator = remember { DiscreteChangeAnimator() }
    val fraction = remember { Animatable(0f) }
    val draw = animator.draw(hit, fraction.value)
    LaunchedEffect(target.animGeneration) {
        fraction.snapTo(1f)
        animator.markStarted()
        fraction.animateTo(0f, tween(durationMillis = ANIMATION_MS, easing = DecelerateEasing))
    }
    return AnimatedSnapshots(hit = hit, draw = draw)
}
