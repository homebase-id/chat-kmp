package id.homebase.chat.calling

import androidx.lifecycle.viewModelScope
import id.homebase.chat.services.calling.CallController
import id.homebase.chat.services.calling.CallEngineFactory
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.FakeCallEngine
import id.homebase.chat.services.calling.FakeCallScript
import id.homebase.chat.services.calling.InMemoryCallDevicePreferences
import id.homebase.chat.services.calling.LoopbackSignaling
import id.homebase.chat.services.calling.ManagedCallMediaDevices
import id.homebase.chat.services.calling.MediaDevice
import id.homebase.chat.services.calling.MediaDeviceKind
import id.homebase.chat.services.calling.MediaDeviceNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class CallDevicesViewModelTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"

    private val builtIn = MediaDevice("mic-builtin", "MacBook Microphone", MediaDeviceKind.Mic, isDefault = true)
    private val usb = MediaDevice("mic-usb", "USB Mic", MediaDeviceKind.Mic, isDefault = false)

    private class Rig(scope: TestScope, hardware: () -> List<MediaDevice>, prefs: InMemoryCallDevicePreferences) {
        val net = LoopbackSignaling(now = { scope.testScheduler.currentTime })
        val applied = mutableListOf<MediaDevice>()
        val managed = mutableListOf<ManagedCallMediaDevices>()
        val me = CallController(
            selfOdinId = "frodo.baggins.demo.rocks",
            signaling = net.endpoint("frodo.baggins.demo.rocks"),
            engineFactory = CallEngineFactory { _ ->
                val devices = ManagedCallMediaDevices({ hardware() }, { applied += it }, prefs).also { it.refresh(); managed += it }
                FakeCallEngine(scope.backgroundScope, FakeCallScript(), "peer", "me", devices = devices)
            },
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )
        val peer = CallController(
            selfOdinId = "samwise.gamgee.demo.rocks",
            signaling = net.endpoint("samwise.gamgee.demo.rocks"),
            engineFactory = CallEngineFactory { _ -> FakeCallEngine(scope.backgroundScope, FakeCallScript(), "me", "peer") },
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )
        val vm = CallViewModel(me, audioRoutes = flowOf(emptyList()), deviceDispatcher = Dispatchers.Main)
        val ui get() = vm.uiState.value
    }

    private fun rigTest(
        hardware: () -> List<MediaDevice>,
        prefs: InMemoryCallDevicePreferences = InMemoryCallDevicePreferences(),
        block: suspend TestScope.(Rig) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = Rig(this, hardware, prefs)
        try {
            block(rig)
        } finally {
            rig.vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.settle(ms: Long = 0) {
        runCurrent()
        if (ms > 0) advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.connect(rig: Rig) {
        rig.peer.startCall(frodo, video = false)
        settle()
        rig.vm.accept()
        settle(1_001)
        assertEquals(CallPhase.Connected, rig.ui.phase)
    }

    @Test
    fun aMicLevelTickDoesNotEmitANewCallUiState() {
        rigTest({ listOf(builtIn) }) { rig ->
            connect(rig)
            val emitted = mutableListOf<CallUiState>()
            val job = backgroundScope.launch { rig.vm.uiState.collect { emitted += it } }
            settle()
            val before = emitted.size

            rig.managed.last().setMicLevel(0.5f)
            settle()
            val afterFirstSound = emitted.size
            assertEquals(0.5f, rig.vm.micLevel.value)
            assertTrue(rig.ui.micLevelSeen)
            assertEquals(before + 1, afterFirstSound)

            for (level in listOf(0.1f, 0.9f, 0.3f, 0f, 0.7f)) {
                rig.managed.last().setMicLevel(level)
                settle()
            }
            assertEquals(0.7f, rig.vm.micLevel.value)
            assertEquals(afterFirstSound, emitted.size)
            job.cancel()
        }
    }

    @Test
    fun selectingADevicePropagatesToTheUiState() {
        val present = listOf(builtIn, usb)
        rigTest({ present }) { rig ->
            connect(rig)
            assertEquals(present, rig.ui.devices)
            assertEquals("mic-builtin", rig.ui.selectedDevices[MediaDeviceKind.Mic])

            rig.vm.selectDevice(MediaDeviceKind.Mic, "mic-usb")
            settle()
            assertEquals("mic-usb", rig.ui.selectedDevices[MediaDeviceKind.Mic])
            assertEquals(listOf(usb), rig.applied)
        }
    }

    @Test
    fun aDisconnectFallsBackAndEmitsTheSnackbarEvent() {
        var present = listOf(builtIn, usb)
        rigTest({ present }) { rig ->
            connect(rig)
            rig.vm.selectDevice(MediaDeviceKind.Mic, "mic-usb")
            settle()
            val events = mutableListOf<CallUiEvent>()
            backgroundScope.launch { rig.vm.events.collect { events += it } }
            settle()

            present = listOf(builtIn)
            rig.managed.single().refresh()
            settle()

            assertEquals("mic-builtin", rig.ui.selectedDevices[MediaDeviceKind.Mic])
            assertEquals(listOf<CallUiEvent>(CallUiEvent.Device(MediaDeviceNotice.Disconnected(MediaDeviceKind.Mic, "USB Mic", "MacBook Microphone"))), events)
        }
    }

    @Test
    fun theStoredChoiceOpensTheNextCall() {
        val present = listOf(builtIn, usb)
        val prefs = InMemoryCallDevicePreferences()
        rigTest({ present }, prefs) { rig ->
            connect(rig)
            rig.vm.selectDevice(MediaDeviceKind.Mic, "mic-usb")
            settle()
            rig.vm.hangup()
            settle(3_000)
            assertTrue(!rig.ui.inProgress)

            rig.peer.startCall(frodo, video = false)
            settle()
            rig.vm.accept()
            settle(1_001)
            assertEquals("mic-usb", rig.ui.selectedDevices[MediaDeviceKind.Mic])
        }
    }
}
