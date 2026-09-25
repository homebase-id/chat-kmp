package id.homebase.core.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.InternalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.haptics.Haptics
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Raw scene events, so one release can carry both pinch fingers at once. */
@OptIn(ExperimentalTestApi::class, InternalTestApi::class, InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class PreviewPinchReleaseTest {

    private class Rig(val test: SkikoComposeUiTest) {
        val engine = FakeCameraEngine(CameraUiState(isBound = true, hasFrontLens = true, minZoom = 1f, maxZoom = 8f))
        var taps = 0
        private var nextId = 0L
        private val center get() = test.onNodeWithTag(PREVIEW_TAG).fetchSemanticsNode().boundsInRoot.center

        fun send(type: PointerEventType, vararg pointers: Pair<Long, Pair<Offset, Boolean>>) {
            test.scene.sendPointerEvent(
                eventType = type,
                pointers = pointers.map { (id, state) ->
                    ComposeScenePointer(PointerId(id), state.first, pressed = state.second, type = PointerType.Touch)
                },
                timeMillis = test.mainClock.currentTime,
            )
            test.mainClock.advanceTimeBy(16)
            test.waitForIdle()
        }

        /** Two fingers spread apart and stay down; returns their ids and final positions. */
        fun pinchOut(): List<Pair<Long, Offset>> {
            val a = nextId++
            val b = nextId++
            val c = center
            send(PointerEventType.Press, a to ((c - Offset(40f, 0f)) to true))
            send(PointerEventType.Press, a to ((c - Offset(40f, 0f)) to true), b to ((c + Offset(40f, 0f)) to true))
            var spread = 40f
            repeat(8) {
                spread += 20f
                send(PointerEventType.Move, a to ((c - Offset(spread, 0f)) to true), b to ((c + Offset(spread, 0f)) to true))
            }
            assertTrue(test.onAllNodesWithTagExists(ZOOM_READOUT_TAG), "the readout should show during the pinch")
            return listOf(a to c - Offset(spread, 0f), b to c + Offset(spread, 0f))
        }

        fun tap() {
            val id = nextId++
            send(PointerEventType.Press, id to (center to true))
            send(PointerEventType.Release, id to (center to false))
        }

        fun readoutShown(): Boolean {
            test.mainClock.advanceTimeBy(2_000)
            test.waitForIdle()
            return test.onAllNodesWithTagExists(ZOOM_READOUT_TAG)
        }
    }

    private fun runRig(block: Rig.() -> Unit) = runSkikoComposeUiTest(size = Size(400f, 800f), density = Density(1f)) {
        val rig = Rig(this)
        setContent {
            HomebaseTheme(darkTheme = true, followsSystemTheme = false, updatesSystemChrome = false) {
                Box(Modifier.size(PhoneSize)) {
                    CameraCaptureContent(
                        engine = rig.engine,
                        allowedModes = CameraModes.PhotoAndVideo,
                        mirrorFront = true,
                        mic = MicPermission(granted = true),
                        onRequestMic = {},
                        haptics = object : Haptics {
                            override fun perform(event: HapticEvent) = Unit
                        },
                        deviceRotation = QuarterTurn.R0,
                        onResult = {},
                        onDismiss = {},
                        preview = { modifier ->
                            Box(modifier.pointerInput(Unit) { detectPreviewTaps(onTap = { rig.taps++ }, onLongPress = {}) })
                        },
                    )
                }
            }
        }
        waitForIdle()
        rig.block()
    }

    private fun Rig.assertPinchEndedAndTapLands() {
        assertTrue(engine.uiState.value.zoomRatio > 1f, "the pinch should zoom in")
        assertEquals(false, readoutShown(), "the zoom readout should fade once the pinch ends")
        tap()
        assertEquals(1, taps, "the first tap after the pinch should focus")
    }

    @Test
    fun bothFingersLiftInOneEvent() = runRig {
        val (a, b) = pinchOut()
        send(PointerEventType.Release, a.first to (a.second to false), b.first to (b.second to false))
        assertPinchEndedAndTapLands()
    }

    @Test
    fun fingersLiftOneAfterTheOther() = runRig {
        val (a, b) = pinchOut()
        send(PointerEventType.Release, a.first to (a.second to false), b.first to (b.second to true))
        send(PointerEventType.Release, b.first to (b.second to false))
        assertPinchEndedAndTapLands()
    }

    @Test
    fun aCancelledPinch() = runRig {
        pinchOut()
        test.scene.cancelPointerInput()
        test.waitForIdle()
        assertPinchEndedAndTapLands()
    }
}

@OptIn(ExperimentalTestApi::class)
private fun SkikoComposeUiTest.onAllNodesWithTagExists(tag: String) =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
