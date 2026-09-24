package id.homebase.core.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.haptics.Haptics
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.coroutines.flow.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Counts scopes across every composition: the HUD sits in a BoxWithConstraints subcomposition.
@OptIn(ExperimentalComposeRuntimeApi::class, ExperimentalTestApi::class)
class CameraHudRecompositionTest {

    private class ScopeCounter : CompositionRegistrationObserver, CompositionObserver {
        var entered = 0
        private val handles = mutableListOf<CompositionObserverHandle>()

        override fun onCompositionRegistered(composition: ObservableComposition) {
            handles += composition.setObserver(this)
        }

        override fun onCompositionUnregistered(composition: ObservableComposition) = Unit
        override fun onBeginComposition(composition: ObservableComposition) = Unit
        override fun onScopeEnter(scope: RecomposeScope) {
            entered++
        }

        override fun onReadInScope(scope: RecomposeScope, value: Any) = Unit
        override fun onScopeExit(scope: RecomposeScope) = Unit
        override fun onEndComposition(composition: ObservableComposition) = Unit
        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) = Unit
        override fun onScopeDisposed(scope: RecomposeScope) = Unit
    }

    private object NoHaptics : Haptics {
        override fun perform(event: HapticEvent) = Unit
    }

    private class Harness(val engine: FakeCameraEngine) {
        var deviceRotation by mutableStateOf(QuarterTurn.R0)
        val counter = ScopeCounter()
    }

    private fun hudTest(initialMode: CaptureMode = CaptureMode.Photo, block: ComposeUiTest.(Harness) -> Unit) =
        runComposeUiTest {
            val harness = Harness(FakeCameraEngine())
            setContent {
                HomebaseTheme(darkTheme = true, followsSystemTheme = false, updatesSystemChrome = false) {
                    Box(Modifier.size(PhoneSize)) {
                        CameraCaptureContent(
                            engine = harness.engine,
                            allowedModes = CameraModes.PhotoAndVideo,
                            initialMode = initialMode,
                            mirrorFront = true,
                            mic = MicPermission(granted = true),
                            onRequestMic = {},
                            haptics = NoHaptics,
                            deviceRotation = harness.deviceRotation,
                            onResult = {},
                            onDismiss = {},
                            preview = { Box(it) },
                        )
                    }
                }
            }
            waitForIdle()
            val recomposers = Recomposer.runningRecomposers.value
            assertTrue(recomposers.isNotEmpty(), "no running recomposer to observe")
            recomposers.forEach { it.observe(harness.counter) }
            mainClock.autoAdvance = false
            block(harness)
        }

    /** Scopes entered in each of the next [frames] frames. */
    private fun ComposeUiTest.scopesPerFrame(counter: ScopeCounter, frames: Int): List<Int> = List(frames) {
        val before = counter.entered
        mainClock.advanceTimeByFrame()
        counter.entered - before
    }

    @Test
    fun aZoomStepRecomposesOnlyTheZoomControls() = hudTest { h ->
        mainClock.advanceTimeBy(2_000)
        val before = h.counter.entered
        listOf(1.1f, 1.2f, 1.3f, 1.4f, 1.5f).forEach { ratio ->
            h.engine.uiState.update { it.copy(zoomRatio = ratio) }
            mainClock.advanceTimeByFrame()
        }
        val perStep = (h.counter.entered - before) / 5
        assertTrue(perStep <= ZOOM_SCOPE_BUDGET, "a zoom step recomposed $perStep scopes")
    }

    @Test
    fun turningTheDeviceAnimatesIconsWithoutRecomposing() = hudTest { h ->
        mainClock.advanceTimeBy(2_000)
        h.deviceRotation = QuarterTurn.R90
        mainClock.advanceTimeByFrame()
        val frames = scopesPerFrame(h.counter, 20)
        assertEquals(List(20) { 0 }, frames, "scopes recomposed per frame of the icon turn")
    }

    @Test
    fun aRunningRecordingDoesNotRecomposeEveryFrame() = hudTest(initialMode = CaptureMode.Video) { h ->
        mainClock.advanceTimeBy(2_000)
        onNodeWithTag(SHUTTER_TAG).performClick()
        mainClock.advanceTimeBy(2_000)
        val frames = scopesPerFrame(h.counter, 30)
        assertTrue(frames.count { it > 0 } <= 1, "recomposed on ${frames.count { it > 0 }} of 30 frames: $frames")
    }

    private companion object {
        // Only the zoom controls, which show the live ratio; the whole HUD was 43.
        const val ZOOM_SCOPE_BUDGET = 8
    }
}
