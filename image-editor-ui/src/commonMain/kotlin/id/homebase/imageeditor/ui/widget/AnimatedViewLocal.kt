package id.homebase.imageeditor.ui.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.ui.MatrixSnapshot

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
 * 250 ms duration matches Signal's `AnimationMatrix` decelerate animation.
 */
@Composable
fun rememberAnimatedSnapshot(target: MatrixSnapshot): MatrixSnapshot {
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

    return target.copy(viewLocal = animated)
}
