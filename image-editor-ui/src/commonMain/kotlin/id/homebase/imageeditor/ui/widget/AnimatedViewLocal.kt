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

class AnimatedSnapshots(val hit: MatrixSnapshot, val draw: MatrixSnapshot)

internal fun MatrixSnapshot.imageChain(): Matrix2D =
    Matrix2D(flipRotate).also {
        it.preConcat(mainImageLocal)
        it.preConcat(mainImageEditor)
    }

private fun animationUndo(from: Matrix2D, to: Matrix2D): Matrix2D {
    val undo = Matrix2D()
    if (!to.invert(undo)) return Matrix2D()
    undo.preConcat(from)
    return undo
}

private fun Matrix2D.withUndo(undo: Matrix2D, fraction: Float): Matrix2D {
    val lerped = Matrix2D()
    for (i in 0 until 6) lerped.values[i] = undo.values[i] * fraction + lerped.values[i] * (1f - fraction)
    return Matrix2D(this).also { it.preConcat(lerped) }
}

// Element-wise lerp, so the in-between matrix may be singular; hit-testing never sees it.
internal class DiscreteChangeAnimator {
    private var generation: Int? = null
    private var previous: MatrixSnapshot? = null
    private var imageUndo = Matrix2D()
    private var cropUndo = Matrix2D()
    private var awaitingStart = false

    fun draw(target: MatrixSnapshot, liveFraction: Float): MatrixSnapshot {
        val prev = previous
        if (generation != null && generation != target.animGeneration && prev != null) {
            val f = if (awaitingStart) 1f else liveFraction
            imageUndo = animationUndo(prev.imageChain().withUndo(imageUndo, f), target.imageChain())
            cropUndo = animationUndo(prev.cropFrameMatrix.withUndo(cropUndo, f), target.cropFrameMatrix)
            awaitingStart = true
        }
        generation = target.animGeneration
        previous = target
        val fraction = if (awaitingStart) 1f else liveFraction
        if (fraction <= 0f) return target
        val frame = target.cropFrameMatrix.withUndo(cropUndo, fraction)
        val rect = RectF()
        frame.mapRect(rect, Bounds.fullBounds())
        return target.copy(
            mainImageEditor = target.mainImageEditor.withUndo(imageUndo, fraction),
            cropFrameMatrix = frame,
            cropRect = rect,
        )
    }

    // The frame after a change must draw the old picture before the Animatable snaps to 1.
    fun markStarted() {
        awaitingStart = false
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
 */
@Composable
fun rememberAnimatedSnapshot(target: MatrixSnapshot): AnimatedSnapshots {
    val v = target.viewLocal.values
    val targetScale = v[Matrix2D.MSCALE_X]
    val targetTx = v[Matrix2D.MTRANS_X]
    val targetTy = v[Matrix2D.MTRANS_Y]
    val anim = tween<Float>(durationMillis = ANIMATION_MS)

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
