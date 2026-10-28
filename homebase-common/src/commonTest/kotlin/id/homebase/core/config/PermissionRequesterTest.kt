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
        ).map { it.requester }
        assertEquals(requesters.size, requesters.toSet().size, "requesters must be unique: $requesters")
    }
}
