package id.homebase.chat.conversationlist

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.EventBus
import kotlinx.coroutines.delay
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.PermissionExtensionConfig
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.api.youauth.TargetDriveAccessRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
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
import kotlinx.coroutines.CompletableDeferred
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
        requester = "test",
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

    private fun MockRequestHandleScope.respondJson(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun jsonEngine(body: String) = MockEngine { respondJson(body) }

    private suspend fun viewModel(engine: MockEngine, eventBus: EventBus = EventBus()): ExtendPermissionViewModel {
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
            eventBus = eventBus,
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

    @Test
    fun aSlowStaleCheckDoesntReplaceANewerVerdict() = runBlocking<Unit> {
        val firstAsked = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstAnswered = CompletableDeferred<Unit>()
        var calls = 0
        val engine = MockEngine {
            val first = ++calls == 1
            if (first) {
                firstAsked.complete(Unit)
                releaseFirst.await()
            }
            respondJson(contextJson(if (first) "" else grantedDrive)).also { if (first) firstAnswered.complete(Unit) }
        }
        val vm = viewModel(engine)
        withTimeout(10.seconds) { firstAsked.await() }

        vm.recheckPermissions()
        withTimeout(10.seconds) { vm.permissionsGranted.first { it } }
        releaseFirst.complete(Unit)
        withTimeout(10.seconds) { firstAnswered.await() }

        assertNull(withTimeoutOrNull(1.seconds) { vm.permissionsGranted.first { !it } }, "the older 'missing' finished last but is stale")
        assertEquals(ExtendPermissionUiState.Idle, vm.uiState.value)
    }

    @Test
    fun aCheckInFlightWhenTheDialogIsDismissedDoesntBringItBack() = runBlocking<Unit> {
        val asked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val answered = CompletableDeferred<Unit>()
        val engine = MockEngine {
            asked.complete(Unit)
            release.await()
            respondJson(contextJson("")).also { answered.complete(Unit) }
        }
        val vm = viewModel(engine)
        withTimeout(10.seconds) { asked.await() }

        vm.dismissDialog()
        release.complete(Unit)
        withTimeout(10.seconds) { answered.await() }

        assertNull(withTimeoutOrNull(1.seconds) { vm.uiState.first { it is ExtendPermissionUiState.ShowDialog } })
        assertEquals(ExtendPermissionUiState.Dismissed, vm.uiState.value)
    }

    @Test
    fun aClosedCheckerNoLongerChecksOnEvents() = runBlocking<Unit> {
        val engine = jsonEngine(contextJson(""))
        val eventBus = EventBus()
        val vm = viewModel(engine, eventBus)
        withTimeout(10.seconds) { vm.permissionsChecked.first { it } }
        // Control: open, the same event makes it check again.
        val before = engine.requestHistory.size
        eventBus.emit(BackendEvent.DriveAuthorizationFailed("403"))
        withTimeout(10.seconds) { while (engine.requestHistory.size == before) delay(20) }
        val asked = engine.requestHistory.size

        vm.close()
        eventBus.emit(BackendEvent.PermissionsExtensionReturned())
        eventBus.emit(BackendEvent.DriveAuthorizationFailed("403"))

        assertNull(withTimeoutOrNull(1.seconds) { while (engine.requestHistory.size == asked) delay(20) })
    }

    @Test
    fun onlyItsOwnMiniAppsReturnMakesItCheckAgain() = runBlocking<Unit> {
        val engine = jsonEngine(contextJson(""))
        val eventBus = EventBus()
        val vm = viewModel(engine, eventBus)
        withTimeout(10.seconds) { vm.permissionsChecked.first { it } }
        val asked = engine.requestHistory.size

        eventBus.emit(BackendEvent.PermissionsExtensionReturned("another"))
        assertNull(withTimeoutOrNull(1.seconds) { while (engine.requestHistory.size == asked) delay(20) })

        eventBus.emit(BackendEvent.PermissionsExtensionReturned("test"))
        withTimeout(10.seconds) { while (engine.requestHistory.size == asked) delay(20) }
    }
}
