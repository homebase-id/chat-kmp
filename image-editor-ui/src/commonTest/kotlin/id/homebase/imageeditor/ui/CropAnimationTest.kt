package id.homebase.imageeditor.ui

import androidx.lifecycle.SavedStateHandle
import id.homebase.imageeditor.core.AspectMode
import id.homebase.imageeditor.core.Bounds
import id.homebase.imageeditor.core.ControlPoint
import id.homebase.imageeditor.core.CropHandles
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.RectF
import id.homebase.imageeditor.core.Size
import id.homebase.imageeditor.ui.widget.DiscreteChangeAnimator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CropAnimationTest {

    private fun viewModel(): CropEditorViewModel {
        val vm = CropEditorViewModel(
            SavedStateHandle(mapOf("requestId" to Uuid.random().toString())),
            CropResultBus(),
        )
        vm.model.onImageReady(Size(600, 450))
        vm.model.setVisibleViewPort(RectF(0f, 0f, 1080f, 1920f))
        vm.model.setCropAspectLock(false)
        vm.snapshotMatrices()
        return vm
    }

    @Test
    fun discreteEditsBumpTheGenerationAndGesturesDoNot() {
        val vm = viewModel()
        val actions = listOf(
            CropEditorUiAction.Rotate90ClockwiseClicked,
            CropEditorUiAction.FlipHorizontalClicked,
            CropEditorUiAction.AspectChanged(AspectMode.Square),
            CropEditorUiAction.UndoClicked,
            CropEditorUiAction.RedoClicked,
            CropEditorUiAction.ResetClicked,
        )
        for (action in actions) {
            val before = vm.matrixSnapshot.animGeneration
            vm.onUiAction(action)
            assertEquals(before + 1, vm.matrixSnapshot.animGeneration, "$action")
        }

        val before = vm.matrixSnapshot.animGeneration
        vm.panMainImage(10f, 5f)
        vm.zoomMainImage(1.2f, 100f to 100f)
        vm.commitMainImageGesture()
        vm.onUiAction(CropEditorUiAction.FreeRotationChanged(5f))
        vm.onUiAction(CropEditorUiAction.FreeRotationReleased)
        assertEquals(before, vm.matrixSnapshot.animGeneration)
    }

    @Test
    fun drawMatrixStartsOnTheOldPictureAndEndsOnTheTarget() {
        val vm = viewModel()
        val old = vm.matrixSnapshot
        vm.onUiAction(CropEditorUiAction.Rotate90ClockwiseClicked)
        val new = vm.matrixSnapshot

        val animator = DiscreteChangeAnimator()
        animator.draw(old, 0f)

        val start = animator.draw(new, 0f)
        assertMatrixClose(old.cropFrameMatrix, start.cropFrameMatrix)
        assertMatrixClose(chain(old), chain(start))

        animator.markStarted()
        val mid = animator.draw(new, 0.5f)
        assertTrue(mid.cropFrameMatrix != new.cropFrameMatrix)

        val end = animator.draw(new, 0f)
        assertMatrixClose(new.cropFrameMatrix, end.cropFrameMatrix)
        assertMatrixClose(chain(new), chain(end))
    }

    @Test
    fun interruptedAnimationContinuesFromTheDisplayedMatrix() {
        val vm = viewModel()
        val animator = DiscreteChangeAnimator()
        animator.draw(vm.matrixSnapshot, 0f)
        vm.onUiAction(CropEditorUiAction.Rotate90ClockwiseClicked)
        animator.draw(vm.matrixSnapshot, 0f)
        animator.markStarted()
        val displayed = animator.draw(vm.matrixSnapshot, 0.4f)

        vm.onUiAction(CropEditorUiAction.FlipHorizontalClicked)
        val restarted = animator.draw(vm.matrixSnapshot, 0.4f)
        assertMatrixClose(displayed.cropFrameMatrix, restarted.cropFrameMatrix)
        assertMatrixClose(chain(displayed), chain(restarted))
    }

    @Test
    fun hitTestingReadsTheTargetWhileTheAnimationIsInFlight() {
        for (action in listOf(
            CropEditorUiAction.Rotate90ClockwiseClicked,
            CropEditorUiAction.FlipHorizontalClicked,
        )) {
            val vm = viewModel()
            val animator = DiscreteChangeAnimator()
            animator.draw(vm.matrixSnapshot, 0f)
            vm.onUiAction(action)
            val target = vm.matrixSnapshot
            animator.draw(target, 0f)
            animator.markStarted()
            val mid = animator.draw(target, 0.5f)
            assertTrue(mid.cropFrameMatrix != target.cropFrameMatrix)

            val m = target.cropToCanvas
            val rect = RectF()
            m.mapRect(rect, Bounds.fullBounds())
            val cp: ControlPoint? = CropHandles.hitTest(rect.left, rect.top, m, 90f)
            assertNotNull(cp, "$action: target corner not hittable mid-flight")
            val hit = m.mapPoint(cp.x, cp.y)
            assertTrue(abs(hit[0] - rect.left) <= 0.5f && abs(hit[1] - rect.top) <= 0.5f)
        }
    }

    private fun chain(s: MatrixSnapshot): Matrix2D =
        Matrix2D(s.flipRotate).also {
            it.preConcat(s.mainImageLocal)
            it.preConcat(s.mainImageEditor)
        }

    private fun assertMatrixClose(expected: Matrix2D, actual: Matrix2D) {
        for (i in 0 until 9) {
            assertTrue(
                abs(expected.values[i] - actual.values[i]) <= 1e-3f * maxOf(1f, abs(expected.values[i])),
                "index $i: expected ${expected.values[i]} was ${actual.values[i]}",
            )
        }
    }
}
