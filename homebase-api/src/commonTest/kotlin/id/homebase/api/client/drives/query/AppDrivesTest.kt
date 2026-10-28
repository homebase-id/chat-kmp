package id.homebase.api.client.drives.query

import id.homebase.api.client.ClientException
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
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
import kotlin.test.assertNull

class AppDrivesTest {

    private suspend fun drives(status: HttpStatusCode, body: String): List<ClientDriveData>? {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(ApiCredentials.create(OdinId("frodo.example.com"), "token", SecureByteArray(ByteArray(16) { 5 })))
        val http = HttpClient(MockEngine { request ->
            assertEquals("/api/v2/apps/filesync/drives", request.url.encodedPath)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        return DriveQueryProvider(http, credentials).getAppDrives("filesync")
    }

    @Test
    fun aRegisteredAppListsItsDrives() = runTest {
        val found = drives(
            HttpStatusCode.OK,
            """[{"targetDrive":{"alias":"cfceb937b868496b91cb3402f18320f8","type":"5da48122e946487a8b9d589642f3c91d"},"name":"Filesync"}]""",
        )
        assertEquals(listOf("Filesync"), found?.map { it.name })
    }

    @Test
    fun aRegisteredAppWithoutDrivesIsStillThere() = runTest {
        assertEquals(emptyList(), drives(HttpStatusCode.OK, "[]"))
    }

    @Test
    fun noAppWithTheSlugIsNull() = runTest {
        assertNull(drives(HttpStatusCode.BadRequest, """{"title":"No app 'filesync'","status":400,"errorCode":"invalidDrive"}"""))
    }

    @Test
    fun anyOtherBadRequestSaysNothing() = runTest {
        assertFailsWith<ClientException> {
            drives(HttpStatusCode.BadRequest, """{"title":"Bad slug","status":400,"errorCode":"argumentError"}""")
        }
    }

    @Test
    fun anyOtherFailureSaysNothing() = runTest {
        assertFailsWith<ForbiddenException> { drives(HttpStatusCode.Forbidden, "") }
    }
}
