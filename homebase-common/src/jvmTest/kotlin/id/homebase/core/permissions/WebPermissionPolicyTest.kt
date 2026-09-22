package id.homebase.core.permissions

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebPermissionPolicyTest {

    @Test
    fun `notification follows the push bridge`() {
        assertTrue(webPermissionGranted(PermissionType.NOTIFICATION, notificationGranted = true))
        assertFalse(webPermissionGranted(PermissionType.NOTIFICATION, notificationGranted = false))
    }

    @Test
    fun `record audio is denied because web has no recorder`() {
        assertFalse(webPermissionGranted(PermissionType.RECORD_AUDIO, notificationGranted = true))
        assertFalse(webPermissionGranted(PermissionType.RECORD_AUDIO, notificationGranted = false))
    }

    @Test
    fun `every other type stays granted regardless of the push bridge`() {
        val granted = listOf(
            PermissionType.GALLERY,
            PermissionType.GALLERY_LIMITED,
            PermissionType.CAMERA,
            PermissionType.LOCATION,
            PermissionType.LOCATION_ALWAYS,
            PermissionType.ACTIVITY,
        )
        granted.forEach { type ->
            assertTrue(webPermissionGranted(type, notificationGranted = false), "$type")
            assertTrue(webPermissionGranted(type, notificationGranted = true), "$type")
        }
    }
}
