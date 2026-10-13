package id.homebase.chat.services.calling

import id.homebase.chat.widget.InMemorySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class ManagedCallMediaDevicesTest {
    private val builtInMic = MediaDevice("mic-builtin", "MacBook Microphone", MediaDeviceKind.Mic, isDefault = true)
    private val usbMic = MediaDevice("mic-usb", "USB Mic", MediaDeviceKind.Mic, isDefault = false)
    private val builtInSpeaker = MediaDevice("spk-builtin", "MacBook Speakers", MediaDeviceKind.Speaker, isDefault = true)
    private val headphones = MediaDevice("spk-bt", "Headphones", MediaDeviceKind.Speaker, isDefault = false)
    private val faceTime = MediaDevice("cam-ft", "FaceTime HD Camera", MediaDeviceKind.Camera, isDefault = false)
    private val deskCam = MediaDevice("cam-usb", "USB Camera", MediaDeviceKind.Camera, isDefault = false)

    private class Hardware(var present: List<MediaDevice>) {
        val applied = mutableListOf<MediaDevice>()
        var failApply = false
    }

    private fun managed(hw: Hardware, prefs: CallDevicePreferences = InMemoryCallDevicePreferences()) = ManagedCallMediaDevices(
        enumerate = { hw.present },
        apply = { if (hw.failApply) error("device busy") else hw.applied += it },
        preferences = prefs,
    ).also { it.refresh() }

    private fun everything() = Hardware(listOf(builtInMic, usbMic, builtInSpeaker, headphones, faceTime, deskCam))

    @Test
    fun startsOnTheSystemDefaultsAndTheFirstCamera() {
        val devices = managed(everything())
        assertEquals(
            mapOf(MediaDeviceKind.Mic to "mic-builtin", MediaDeviceKind.Speaker to "spk-builtin", MediaDeviceKind.Camera to "cam-ft"),
            devices.selected.value,
        )
        assertEquals(6, devices.devices.value.size)
    }

    @Test
    fun selectingSwitchesTheDeviceUpdatesSelectionAndRemembersIt() {
        val hw = everything()
        val prefs = InMemoryCallDevicePreferences()
        val devices = managed(hw, prefs)
        devices.select(MediaDeviceKind.Mic, "mic-usb")
        assertEquals(listOf(usbMic), hw.applied)
        assertEquals("mic-usb", devices.selected.value[MediaDeviceKind.Mic])
        assertEquals("mic-usb", prefs.get(MediaDeviceKind.Mic))
        assertNull(prefs.get(MediaDeviceKind.Speaker))
    }

    @Test
    fun aDeviceThatFailsToSwitchLeavesTheSelectionAndPreferenceAlone() {
        val hw = everything()
        val prefs = InMemoryCallDevicePreferences()
        val devices = managed(hw, prefs)
        hw.failApply = true
        devices.select(MediaDeviceKind.Mic, "mic-usb")
        assertEquals("mic-builtin", devices.selected.value[MediaDeviceKind.Mic])
        assertNull(prefs.get(MediaDeviceKind.Mic))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun unpluggingTheSelectedDeviceFallsBackToTheDefaultAndSaysSoOnce() = runTest(UnconfinedTestDispatcher()) {
        val hw = everything()
        val prefs = InMemoryCallDevicePreferences()
        val devices = managed(hw, prefs)
        devices.select(MediaDeviceKind.Mic, "mic-usb")
        val notices = mutableListOf<MediaDeviceNotice>()
        val job = devices.events.onEach { notices += it }.launchIn(this)

        hw.present = hw.present - usbMic
        devices.refresh()
        devices.refresh()

        assertEquals("mic-builtin", devices.selected.value[MediaDeviceKind.Mic])
        assertEquals(builtInMic, hw.applied.last())
        assertEquals(listOf<MediaDeviceNotice>(MediaDeviceNotice.Disconnected(MediaDeviceKind.Mic, "USB Mic", "MacBook Microphone")), notices)
        assertEquals("mic-usb", prefs.get(MediaDeviceKind.Mic), "the user's choice is remembered for when the mic is back")
        job.cancel()
    }

    @Test
    fun unpluggingTheSelectedCameraMovesToTheNextOne() {
        val hw = everything()
        val devices = managed(hw)
        hw.present = hw.present - faceTime
        devices.refresh()
        assertEquals("cam-usb", devices.selected.value[MediaDeviceKind.Camera])
    }

    @Test
    fun theRememberedDevicesOpenTheNextCallWhenPresentAndTheDefaultsWhenNot() {
        val prefs = InMemoryCallDevicePreferences()
        managed(everything(), prefs).apply {
            select(MediaDeviceKind.Mic, "mic-usb")
            select(MediaDeviceKind.Speaker, "spk-bt")
        }

        val hw = Hardware(listOf(builtInMic, usbMic, builtInSpeaker, faceTime))
        val nextCall = managed(hw, prefs)
        nextCall.applyPreferences()
        assertEquals("mic-usb", nextCall.selected.value[MediaDeviceKind.Mic])
        assertEquals("spk-builtin", nextCall.selected.value[MediaDeviceKind.Speaker], "headphones are gone, so the default stays")
        assertEquals(listOf(usbMic), hw.applied)
    }

    @Test
    fun noDevicesOfAKindReportsNullSelectionWithoutFailing() {
        val devices = managed(Hardware(emptyList()))
        assertTrue(devices.devices.value.isEmpty())
        assertFalse(devices.selected.value.values.any { it != null })
    }

    @Test
    fun micLevelNormalisesAndClamps() {
        assertEquals(0f, normalizeMicLevel(0))
        assertEquals(1f, normalizeMicLevel(40000))
        assertEquals(0.5f, normalizeMicLevel(8192), 0.01f)
    }

    @Test
    fun settingsBackedPreferencesRoundTripPerKind() {
        val prefs = SettingsCallDevicePreferences(InMemorySettings())
        assertNull(prefs.get(MediaDeviceKind.Camera))
        prefs.set(MediaDeviceKind.Camera, "cam-usb")
        prefs.set(MediaDeviceKind.Mic, "mic-usb")
        assertEquals("cam-usb", prefs.get(MediaDeviceKind.Camera))
        assertEquals("mic-usb", prefs.get(MediaDeviceKind.Mic))
        assertNull(prefs.get(MediaDeviceKind.Speaker))
    }

    @Test
    fun cameraMirrorFollowsTheDeviceKind() {
        assertTrue(cameraMirrored("FaceTime HD Camera", null))
        assertTrue(cameraMirrored("USB Camera", "USB"))
        assertFalse(cameraMirrored("OBS Virtual Camera", "UNKNOWN"))
        assertFalse(cameraMirrored("Ada's iPhone Camera", "WIRELESS"))
    }

    @Test
    fun pickCapabilityPrefersNear720pAtThirtyFps() {
        val picked = pickCapability(listOf(Capability(640, 480, 30), Capability(1280, 720, 60), Capability(1280, 720, 30), Capability(1920, 1080, 30)))
        assertEquals(Capability(1280, 720, 30), picked)
        assertNull(pickCapability(emptyList()))
    }
}
