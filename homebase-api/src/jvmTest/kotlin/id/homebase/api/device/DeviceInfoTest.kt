package id.homebase.api.device

import kotlin.test.Test
import kotlin.test.assertTrue

class DeviceInfoTest {

    // YouAuthorizationParams.toQueryString() drops client_info when it is empty, so a blank
    // device name would silently register with no name at all rather than fail loudly.
    @Test
    fun `device name is never blank`() {
        assertTrue(deviceDisplayName().isNotBlank())
    }

    @Test
    fun `device platform is never blank`() {
        assertTrue(devicePlatform().isNotBlank())
    }
}
