package id.homebase.api.client.connections

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CircleListTest {

    private val credentialsManager = CredentialsManager()
    private val provider = ConnectionNetworkProvider(
        HttpClient(MockEngine {
            respond(
                """[{"circle":{"id":"aa","name":"Family","designation":"personal"},"members":["sam.test"]},
                    {"circle":{"id":"bb","name":"Sam","designation":"individual"},"members":["sam.test"]}]""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }),
        credentialsManager,
    )

    @BeforeTest
    fun signIn() = runBlocking {
        credentialsManager.setActiveCredentials(ApiCredentials.create(OdinId("frodo.test"), "token", SecureByteArray(ByteArray(16))))
    }

    @Test
    fun onePersonCirclesAreNotListed() = runTest {
        assertEquals(listOf("Family"), provider.getCirclesWithMembers().map { it.circle.name })
    }
}
