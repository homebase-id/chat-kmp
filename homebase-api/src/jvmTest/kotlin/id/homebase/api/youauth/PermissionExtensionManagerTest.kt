package id.homebase.api.youauth

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PermissionExtensionManagerTest {

    private val me = OdinId("me.example.com")
    private val driveAlias = "1f2c3d4e5a6b7c8d9e0f1a2b3c4d5e6f"
    private val driveType = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"

    private val config = PermissionExtensionConfig(
        appId = "0c3f4e5a6b7c8d9e0f1a2b3c4d5e6f70",
        appName = "Test App",
        drives = listOf(
            TargetDriveAccessRequest(
                alias = driveAlias,
                type = driveType,
                name = "Test drive",
                description = "Test drive",
                permissions = listOf(DrivePermission.Read, DrivePermission.Write),
            )
        ),
        permissions = listOf(AppPermissionType.SendPushNotifications),
        returnUrl = { "https://return.example.com" },
    )

    // No X-SSE header, so SecurityContextProvider treats the body as plaintext.
    private fun contextJson(driveGrants: String, keys: String) = """
        {
          "caller": { "odinId": "$me", "securityLevel": "owner" },
          "permissionContext": {
            "permissionGroups": [
              { "driveGrants": [$driveGrants], "permissionSet": { "keys": [$keys] } }
            ]
          }
        }
    """.trimIndent()

    private val grantedDrive = """
        {
          "permissionedDrive": {
            "drive": { "alias": "$driveAlias", "type": "$driveType" },
            "permission": "readwrite"
          }
        }
    """.trimIndent()

    private fun jsonEngine(body: String) = MockEngine {
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private suspend fun manager(engine: MockEngine): PermissionExtensionManager {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(
            ApiCredentials.create(
                domain = me,
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray(ByteArray(16) { 1 }),
            )
        )
        return PermissionExtensionManager.create(
            securityContextProvider = SecurityContextProvider(HttpClient(engine), credentials),
            hostIdentity = me.domainName,
        )
    }

    @Test
    fun unreachableSecurityContext_isUnknown_notAllGranted() = runTest {
        val manager = manager(MockEngine { respondError(HttpStatusCode.Unauthorized) })
        assertEquals(PermissionCheckResult.Unknown, manager.getMissingPermissions(config))
    }

    @Test
    fun everythingGranted_isAllGranted() = runTest {
        val manager = manager(
            jsonEngine(contextJson(grantedDrive, AppPermissionType.SendPushNotifications.value.toString()))
        )
        assertEquals(PermissionCheckResult.AllGranted, manager.getMissingPermissions(config))
    }

    @Test
    fun ungrantedDriveAndPermission_areReportedAsMissing() = runTest {
        val manager = manager(jsonEngine(contextJson("", "")))

        val result = manager.getMissingPermissions(config)

        assertTrue(result is PermissionCheckResult.Missing, "expected Missing, got $result")
        assertEquals(listOf(driveAlias), result.details.missingDrives.map { it.alias })
        assertEquals(
            listOf(AppPermissionType.SendPushNotifications),
            result.details.missingPermissions,
        )
    }
}
