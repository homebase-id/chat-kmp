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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDrivesTest {

    private suspend fun registered(status: HttpStatusCode, body: String): Boolean {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(ApiCredentials.create(OdinId("frodo.example.com"), "token", SecureByteArray(ByteArray(16) { 5 })))
        val http = HttpClient(MockEngine { request ->
            assertEquals("/api/v2/apps/filesync/drives", request.url.encodedPath)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        return DriveQueryProvider(http, credentials).isAppRegistered("filesync")
    }

    @Test
    fun anAppWithDrivesIsRegistered() = runTest {
        assertTrue(
            registered(
                HttpStatusCode.OK,
                """[{"targetDrive":{"alias":"cfceb937b868496b91cb3402f18320f8","type":"5da48122e946487a8b9d589642f3c91d"},"name":"Filesync"}]""",
            )
        )
    }

    @Test
    fun aRegisteredAppWithoutDrivesIsStillThere() = runTest {
        assertTrue(registered(HttpStatusCode.OK, "[]"))
    }

    @Test
    fun noAppWithTheSlugIsNotRegistered() = runTest {
        assertFalse(registered(HttpStatusCode.BadRequest, """{"title":"No app 'filesync'","status":400,"errorCode":"invalidDrive"}"""))
    }

    @Test
    fun anyOtherBadRequestSaysNothing() = runTest {
        assertFailsWith<ClientException> {
            registered(HttpStatusCode.BadRequest, """{"title":"Bad slug","status":400,"errorCode":"argumentError"}""")
        }
    }

    @Test
    fun anyOtherFailureSaysNothing() = runTest {
        assertFailsWith<ForbiddenException> { registered(HttpStatusCode.Forbidden, "") }
    }
}
