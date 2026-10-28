package id.homebase.api.youauth

import id.homebase.api.client.eventbus.BackendEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionCallbackTest {

    @Test
    fun everyPlatformsReturnUrlReadsTheSame() {
        listOf(
            "https://frodo.example.com/apps/chat/permission-callback?for=vault",
            "homebase-fchat://permission-callback?for=vault",
            "http://localhost:51234/permission-callback?for=vault",
        ).forEach { url ->
            assertEquals(PermissionCallback(canceled = false, requester = "vault"), PermissionCallback.parse(url), url)
            assertEquals(PermissionCallback(canceled = true, requester = "vault"), PermissionCallback.parse("$url&status=canceled"), url)
        }
    }

    @Test
    fun anOlderUrlWithoutTheRequesterIsEveryones() {
        assertEquals(
            BackendEvent.PermissionsExtensionCanceled(null),
            PermissionCallback.parse("homebase-fchat://permission-callback?status=cancelled").event,
        )
    }

    @Test
    fun theCallbackUrlCarriesTheRequester() {
        fun config(returnUrl: String) = PermissionExtensionConfig(
            appId = "a", appName = "A", drives = emptyList(), permissions = emptyList(), returnUrl = { returnUrl }, requester = "vault",
        )
        assertEquals("homebase-fchat://permission-callback?for=vault", config("homebase-fchat://permission-callback").callbackUrl())
        assertEquals("https://x/cb?state=1&for=vault", config("https://x/cb?state=1").callbackUrl())
    }
}
