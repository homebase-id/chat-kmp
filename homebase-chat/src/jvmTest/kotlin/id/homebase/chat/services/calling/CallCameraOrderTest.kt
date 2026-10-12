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
