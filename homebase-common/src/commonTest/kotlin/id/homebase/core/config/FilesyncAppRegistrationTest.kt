package id.homebase.core.config

import id.homebase.api.youauth.TargetDriveAccessRequest
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilesyncAppRegistrationTest {

    @Test
    fun theInstallSendsTheDesktopsOwnDefinition() {
        val url = Url(filesyncAppRegistration.url("frodo.example.com", "https://frodo.example.com/apps/chat/permission-callback"))

        assertEquals("/owner/appreg", url.encodedPath)
        assertEquals(
            mapOf(
                "appId" to "03ae1a49cdae483f9da7fedc0e8f20b9",
                "n" to "Homebase Filesync",
                "as" to "filesync",
                "d" to """[{"a":"cfceb937-b868-496b-91cb-3402f18320f8","t":"5da48122-e946-487a-8b9d-589642f3c91d","n":"Filesync","d":"Files synced with your computers by Homebase Filesync","p":3,"ds":"filesync","ts":"filesync","sm":"selectiveShare"}]""",
                "cd" to "[]",
                "ac" to """[{"s":"filesync","n":"Filesync","d":"People who can see the files you share through Homebase Filesync","e":"📁","g":[{"a":"cfceb937-b868-496b-91cb-3402f18320f8","t":"5da48122-e946-487a-8b9d-589642f3c91d","p":1}]}]""",
                "return" to "https://frodo.example.com/apps/chat/permission-callback",
                "cancel" to "https://frodo.example.com/apps/chat/permission-callback?status=canceled",
            ),
            url.parameters.entries().associate { (key, values) -> key to values.single() },
        )
    }

    @Test
    fun chatOnlyAsksForAccessToTheDrive() {
        assertEquals(
            """[{"a":"cfceb937-b868-496b-91cb-3402f18320f8","t":"5da48122-e946-487a-8b9d-589642f3c91d","p":3,"ts":"filesync"}]""",
            TargetDriveAccessRequest.encodeList(filesyncTargetDriveAccessRequest),
        )
    }

    @Test
    fun vaultAsksForAPrivateDrive() {
        val drive = TargetDriveAccessRequest.encodeList(vaultTargetDriveAccessRequest)
        assertTrue(drive.endsWith(""","sm":"private"}]"""), drive)
    }
}
