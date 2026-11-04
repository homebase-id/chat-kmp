package id.homebase.api.youauth

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SecurityContextProviderTest {

    @Test
    fun aZeroKnowledgeGrantKeepsItsWriteOnlyKey() = runTest {
        val body = """
            {
              "caller": { "odinId": "frodo.test", "securityLevel": "owner" },
              "permissionContext": { "permissionGroups": [ { "driveGrants": [ {
                "permissionedDrive": { "drive": { "alias": "cfceb937b868496b91cb3402f18320f8", "type": "e8475dc46cb4b6651c2d0dbd0f3aad5f" }, "permission": "readWrite" },
                "zkAppKeyEncryptedStorageKey": "drive-key",
                "zkStorageKeyEncryptedWriteOnlyPrivateKey": "write-only-key"
              } ] } ] }
            }
        """.trimIndent()
        val credentials = CredentialsManager().apply {
            setActiveCredentials(ApiCredentials.create(OdinId("frodo.test"), "token", SecureByteArray(ByteArray(16) { 1 })))
        }
        val engine = MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }

        val grant = SecurityContextProvider(HttpClient(engine), credentials).getSecurityContext()!!.driveGrants.single()

        assertEquals("drive-key", grant.zkAppKeyEncryptedStorageKey)
        assertEquals("write-only-key", grant.zkStorageKeyEncryptedWriteOnlyPrivateKey)
    }
}
