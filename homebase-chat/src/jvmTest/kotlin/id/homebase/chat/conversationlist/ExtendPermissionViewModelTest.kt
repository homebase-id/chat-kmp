package id.homebase.chat.conversationlist

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.PermissionExtensionConfig
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.api.youauth.TargetDriveAccessRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class ExtendPermissionViewModelTest {

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
        permissions = emptyList(),
        returnUrl = { "https://return.example.com" },
    )

    // No X-SSE header, so SecurityContextProvider treats the body as plaintext.
    private fun contextJson(driveGrants: String) = """
        {
          "caller": { "odinId": "$me", "securityLevel": "owner" },
          "permissionContext": {
            "permissionGroups": [ { "driveGrants": [$driveGrants] } ]
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

    private suspend fun viewModel(engine: MockEngine): ExtendPermissionViewModel {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(
            ApiCredentials.create(
                domain = me,
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray(ByteArray(16) { 1 }),
            )
        )
        return ExtendPermissionViewModel(
            securityContextProvider = SecurityContextProvider(HttpClient(engine), credentials),
            credentialsManager = credentials,
            eventBus = EventBus(),
            config = config,
        )
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun unreachableSecurityContext_doesNotReportPermissionsGranted() = runBlocking<Unit> {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val vm = viewModel(engine)

        val granted = withTimeoutOrNull(2.seconds) { vm.permissionsGranted.first { it } }

        assertNull(granted, "a 401 on /auth/context must not read as a grant")
        assertEquals(1, engine.requestHistory.size, "the context must actually have been asked for")
        assertFalse(vm.permissionsChecked.value, "an unknown verdict must not be cached as checked")
        assertEquals(ExtendPermissionUiState.Idle, vm.uiState.value)
    }

    @Test
    fun everythingGranted_reportsPermissionsGranted() = runBlocking<Unit> {
        val vm = viewModel(jsonEngine(contextJson(grantedDrive)))

        withTimeout(10.seconds) { vm.permissionsChecked.first { it } }

        assertTrue(vm.permissionsGranted.value)
    }

    @Test
    fun missingDrive_reportsNotGranted_andSurfacesTheDialog() = runBlocking<Unit> {
        val vm = viewModel(jsonEngine(contextJson("")))

        withTimeout(10.seconds) { vm.permissionsChecked.first { it } }

        assertFalse(vm.permissionsGranted.value)
        assertEquals(ExtendPermissionUiState.ShowDialog(config.appName), vm.uiState.value)
        assertNotNull(vm.buildExtendPermissionUrl(), "the missing drive must reach the extend URL")
    }
}
