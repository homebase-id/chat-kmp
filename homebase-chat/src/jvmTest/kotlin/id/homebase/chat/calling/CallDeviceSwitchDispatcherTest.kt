package id.homebase.chat.calling

import androidx.lifecycle.viewModelScope
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallMediaDevices
import id.homebase.chat.services.calling.CallSession
import id.homebase.chat.services.calling.CallState
import id.homebase.chat.services.calling.CallVideos
import id.homebase.chat.services.calling.MediaDevice
import id.homebase.chat.services.calling.MediaDeviceKind
import id.homebase.chat.services.calling.MediaDeviceNotice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

class CallDeviceSwitchDispatcherTest {
    private class HangingDevices(private val release: CountDownLatch) : CallMediaDevices {
        override val devices = MutableStateFlow(emptyList<MediaDevice>())
        override val selected = MutableStateFlow(emptyMap<MediaDeviceKind, String?>())
        override val micLevel = MutableStateFlow(0f)
        override val events = MutableSharedFlow<MediaDeviceNotice>()
        override fun select(kind: MediaDeviceKind, id: String) { release.await() }
    }

    private class DevicesSession(devices: CallMediaDevices) : CallSession {
        override val state = MutableStateFlow(CallState())
        override val transitions get() = state
        override val videos = MutableStateFlow(CallVideos())
        override val mediaDevices = MutableStateFlow<CallMediaDevices?>(devices)
        override fun startCall(peer: String, video: Boolean) = Unit
        override fun accept() = Unit
        override fun decline() = Unit
        override fun hangup() = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun setVideo(on: Boolean) = Unit
        override fun switchCamera() = Unit
        override fun setAudioRoute(route: AudioRoute) = Unit
    }

    @Test
    fun hungDeviceSwitchesLeaveTheSharedDefaultPoolFree() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val release = CountDownLatch(1)
        val vm = CallViewModel(DevicesSession(HangingDevices(release)), audioRoutes = flowOf(emptyList()))
        val probe = CoroutineScope(Dispatchers.Default)
        try {
            repeat(Runtime.getRuntime().availableProcessors() + 1) { vm.selectDevice(MediaDeviceKind.Mic, "loom") }
            val ran = CountDownLatch(1)
            probe.launch { ran.countDown() }
            assertTrue(ran.await(2, TimeUnit.SECONDS), "Dispatchers.Default starved by device switches stuck in native audio")
        } finally {
            release.countDown()
            probe.cancel()
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
