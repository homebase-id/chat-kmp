package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals

class CallCameraOrderTest {
    @Test
    fun theBuiltInCameraIsOpenedBeforeTheVirtualCameraAVFoundationListsFirst() {
        val listed = listOf("OBS Virtual Camera", "FaceTime HD Camera", "Biswa iphone Camera")
        assertEquals(
            listOf("FaceTime HD Camera", "Biswa iphone Camera", "OBS Virtual Camera"),
            preferredCameraOrder(listed) { it },
        )
    }

    @Test
    fun camerasWithoutHintsKeepTheirListedOrder() {
        val listed = listOf("USB Camera", "HD Webcam")
        assertEquals(listed, preferredCameraOrder(listed) { it })
    }
}

class CallCameraFallbackTest {
    @Test
    fun aSilentCameraFallsThroughTheListInOrderAndEndsWhenNoneIsLeft() {
        val order = listOf("facetime", "usb", "obs")
        assertEquals("usb", nextCameraAfterSilence(order, listOf("facetime")))
        assertEquals("obs", nextCameraAfterSilence(order, listOf("facetime", "usb")))
        assertEquals(null, nextCameraAfterSilence(order, order))
    }

    @Test
    fun theCaptureModeIsTheNearest720pAtThirtyFpsNotTheDevicesDefault() {
        val modes = listOf(Capability(1920, 1080, 30), Capability(1280, 720, 15), Capability(1280, 720, 30), Capability(640, 480, 30))
        assertEquals(Capability(1280, 720, 30), pickCapability(modes))
    }
}
