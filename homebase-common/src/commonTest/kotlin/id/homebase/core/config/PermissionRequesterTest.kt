package id.homebase.core.config

import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionRequesterTest {

    @Test
    fun everyMiniAppAnswersOnlyToItsOwnReturn() {
        val requesters = listOf(
            getPermissionExtensionConfig(),
            getFeedPermissionExtensionConfig(),
            getMomentsPermissionExtensionConfig(),
            getVaultPermissionExtensionConfig(),
            getEmailPermissionExtensionConfig(),
            getFilesyncPermissionExtensionConfig(isInstalled = { true }),
            getWebDropPermissionExtensionConfig(),
            getLocationPermissionExtensionConfig(),
            getStickerPermissionExtensionConfig(),
            getCirclePermissionExtensionConfig(),
        ).map { it.requester }
        assertEquals(requesters.size, requesters.toSet().size, "requesters must be unique: $requesters")
    }

    @Test
    fun circleCreationNeedsTheProfileDriveStorageKey() {
        val drive = getCirclePermissionExtensionConfig().drives.single()
        assertEquals(true, drive.requireStorageKey)
        assertEquals(listOf(id.homebase.api.youauth.DrivePermission.Read), drive.permissions)
    }
}
