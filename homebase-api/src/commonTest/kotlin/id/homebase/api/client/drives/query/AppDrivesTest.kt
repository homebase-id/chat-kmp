package id.homebase.api.client.drives.query

import id.homebase.api.client.ClientException
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.youauth.AppInstallState
import id.homebase.api.youauth.AppRegistrationParams
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.TargetDriveAccessRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppDrivesTest {

    private val alias = "cfceb937-b868-496b-91cb-3402f18320f8"
    private val type = "5da48122-e946-487a-8b9d-589642f3c91d"
    private val ok = HttpStatusCode.OK to "[]"

    private val theDrive =
        """{"targetDrive":{"alias":"cfceb937b868496b91cb3402f18320f8","type":"5da48122e946487a8b9d589642f3c91d"},"name":""}"""
    private val anotherDriveOfTheType =
        """{"targetDrive":{"alias":"11111111111141118111111111111111","type":"5da48122e946487a8b9d589642f3c91d"},"name":""}"""

    private fun drivesOfType(vararg drives: String) =
        HttpStatusCode.OK to """{"totalPages":1,"results":[${drives.joinToString()}]}"""

    private suspend fun state(
        app: Pair<HttpStatusCode, String>,
        drives: Pair<HttpStatusCode, String> = HttpStatusCode.InternalServerError to "",
    ): AppInstallState {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(ApiCredentials.create(OdinId("frodo.example.com"), "token", SecureByteArray(ByteArray(16) { 5 })))
        val http = HttpClient(MockEngine { request ->
            val (status, body) = when (request.url.encodedPath) {
                "/api/v2/apps/filesync/drives" -> app
                "/api/v2/drives" -> drives
                else -> error("unexpected ${request.url.encodedPath}")
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        return DriveQueryProvider(http, credentials).appInstallState(
            AppRegistrationParams(
                appId = "03ae1a49cdae483f9da7fedc0e8f20b9",
                name = "Homebase Filesync",
                appSlug = "filesync",
                drives = listOf(TargetDriveAccessRequest(alias, type, "Filesync", "", listOf(DrivePermission.Read))),
                circles = emptyList(),
            ),
        )
    }

    @Test
    fun anAppWhoseDriveExistsIsInstalled() = runTest {
        assertEquals(AppInstallState.Installed, state(ok, drivesOfType(anotherDriveOfTheType, theDrive)))
    }

    @Test
    fun aSlugHeldWithoutTheDriveIsTaken() = runTest {
        assertEquals(AppInstallState.SlugTaken, state(ok, drivesOfType()))
    }

    @Test
    fun anotherDriveOfTheSameTypeIsntTheDrive() = runTest {
        assertEquals(AppInstallState.SlugTaken, state(ok, drivesOfType(anotherDriveOfTheType)))
    }

    @Test
    fun whenTheDriveCantBeCheckedTheCheckFails() = runTest {
        assertFailsWith<ForbiddenException> { state(ok, HttpStatusCode.Forbidden to "") }
    }

    @Test
    fun noAppWithTheSlugIsNotInstalled() = runTest {
        assertEquals(
            AppInstallState.NotInstalled,
            state(HttpStatusCode.BadRequest to """{"title":"No app 'filesync'","status":400,"errorCode":"invalidDrive"}"""),
        )
    }

    @Test
    fun anyOtherBadRequestSaysNothing() = runTest {
        assertFailsWith<ClientException> {
            state(HttpStatusCode.BadRequest to """{"title":"Bad slug","status":400,"errorCode":"argumentError"}""")
        }
    }

    @Test
    fun anyOtherFailureSaysNothing() = runTest {
        assertFailsWith<ForbiddenException> { state(HttpStatusCode.Forbidden to "") }
    }
}
