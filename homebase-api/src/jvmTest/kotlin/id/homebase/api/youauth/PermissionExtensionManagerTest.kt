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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    private fun grant(permission: String, hasStorageKey: Boolean?, alias: String = driveAlias) = """
        {
          "permissionedDrive": {
            "drive": { "alias": "$alias", "type": "$driveType" },
            "permission": "$permission"
          }${hasStorageKey?.let { ", \"hasStorageKey\": $it" }.orEmpty()}
        }
    """.trimIndent()

    private fun contextWithGroups(vararg groups: String) = """
        {
          "caller": { "odinId": "$me", "securityLevel": "owner" },
          "permissionContext": { "permissionGroups": [${groups.joinToString { "{ \"driveGrants\": [$it] }" }}] }
        }
    """.trimIndent()

    private suspend fun context(body: String): SecurityContext {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(
            ApiCredentials.create(domain = me, clientAccessToken = "t", sharedSecret = SecureByteArray(ByteArray(16) { 1 })),
        )
        return assertNotNull(SecurityContextProvider(HttpClient(jsonEngine(body)), credentials).getSecurityContext())
    }

    private val keyedReadConfig = config.copy(
        drives = listOf(
            TargetDriveAccessRequest(
                alias = driveAlias,
                type = driveType,
                name = "",
                description = "",
                permissions = listOf(DrivePermission.Read),
                requireStorageKey = true,
            ),
        ),
        permissions = emptyList(),
    )

    @Test
    fun hasStorageKeyIsParsedAndAbsentMeansNoKey() = runTest {
        val keyed = context(contextWithGroups(grant("read", hasStorageKey = true)))
        val keyless = context(contextWithGroups(grant("read", hasStorageKey = false)))
        val legacy = context(contextWithGroups(grant("read", hasStorageKey = null)))

        assertTrue(keyed.permissionContext.permissionGroups.single().driveGrants!!.single().hasStorageKey)
        assertTrue(keyed.canDecrypt(driveAlias, driveType))
        assertFalse(keyless.canDecrypt(driveAlias, driveType))
        assertFalse(legacy.canDecrypt(driveAlias, driveType))
    }

    @Test
    fun onlyAKeyedReadGrantOnThatDriveDecrypts() = runTest {
        val dashed = "${driveAlias.substring(0, 8)}-${driveAlias.substring(8, 12)}-${driveAlias.substring(12, 16)}-" +
            "${driveAlias.substring(16, 20)}-${driveAlias.substring(20)}"
        val keyedWrite = context(contextWithGroups(grant("write", hasStorageKey = true)))
        val otherDrive = context(contextWithGroups(grant("read", hasStorageKey = true, alias = "ffffffffffffffffffffffffffffffff")))
        val anonymousPlusWrite = context(contextWithGroups(grant("read", hasStorageKey = false), grant("write", hasStorageKey = false)))
        val keyedAmongGroups = context(contextWithGroups(grant("read", hasStorageKey = false), grant("readwrite", hasStorageKey = true)))

        assertFalse(keyedWrite.canDecrypt(driveAlias, driveType))
        assertFalse(otherDrive.canDecrypt(driveAlias, driveType))
        assertFalse(anonymousPlusWrite.canDecrypt(driveAlias, driveType))
        assertTrue(keyedAmongGroups.canDecrypt(dashed, driveType.uppercase()))
        assertEquals(
            setOf(DrivePermission.Read, DrivePermission.Write),
            anonymousPlusWrite.drivePermissions(driveAlias, driveType).toSet(),
        )
    }

    @Test
    fun mergingGrantsKeepsTheStorageKey() {
        fun grantOf(permission: DrivePermission, key: Boolean) = DriveGrant(
            PermissionedDrive(DriveReference(driveAlias, driveType), listOf(permission)),
            hasStorageKey = key,
        )
        val merged = getUniqueDrivesWithHighestPermission(
            listOf(grantOf(DrivePermission.Read, key = false), grantOf(DrivePermission.Write, key = true)),
        ).single()

        assertTrue(merged.hasStorageKey)
        assertEquals(listOf(DrivePermission.Read, DrivePermission.Write), merged.permissionedDrive.permission)
    }

    // Every app context carries Read on each anonymous drive, keyless; that must not count.
    @Test
    fun aDriveThatNeedsTheKeyIsMissingUntilAKeyedReadArrives() = runTest {
        val anonymousOnly = context(contextWithGroups(grant("read", hasStorageKey = false)))
        val keyed = context(contextWithGroups(grant("read", hasStorageKey = true)))
        val manager = manager(jsonEngine(""))

        val missing = manager.getMissingPermissions(keyedReadConfig, anonymousOnly)
        assertTrue(missing is PermissionCheckResult.Missing, "expected Missing, got $missing")
        assertEquals(listOf(driveAlias), missing.details.missingDrives.map { it.alias })
        assertEquals(PermissionCheckResult.AllGranted, manager.getMissingPermissions(keyedReadConfig, keyed))
    }

    @Test
    fun aDriveThatDoesNotAskForTheKeyStillAcceptsAKeylessGrant() = runTest {
        val keyless = context(contextWithGroups(grant("readwrite", hasStorageKey = false)))
        val keylessConfig = config.copy(permissions = emptyList())

        assertEquals(PermissionCheckResult.AllGranted, manager(jsonEngine("")).getMissingPermissions(keylessConfig, keyless))
        assertEquals(
            PermissionCheckResult.AllGranted,
            manager(jsonEngine(contextWithGroups(grant("readwrite", hasStorageKey = null)))).getMissingPermissions(keylessConfig),
        )
    }

    @Test
    fun theStorageKeyFlagNeverReachesTheRequestUrl() {
        assertEquals(
            keyedReadConfig.drives.single().copy(requireStorageKey = false).toJson(),
            keyedReadConfig.drives.single().toJson(),
        )
    }
}
