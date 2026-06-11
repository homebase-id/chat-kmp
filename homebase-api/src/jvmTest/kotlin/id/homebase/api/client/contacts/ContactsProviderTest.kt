@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ContactsProviderTest {

    private val testDomain = OdinId("test.homebase.id")
    private val uniqueId = "11111111-1111-1111-1111-111111111111"
    private val tagA = "22222222-2222-2222-2222-222222222222"
    private val tagB = "33333333-3333-3333-3333-333333333333"
    private val tagC = "44444444-4444-4444-4444-444444444444"

    private val jsonHeaders =
        headersOf("Content-Type" to listOf(ContentType.Application.Json.toString()))

    private suspend fun provider(engine: MockEngine): ContactsProvider {
        val cm = CredentialsManager()
        val creds = ApiCredentials.create(
            domain = testDomain,
            clientAccessToken = "test-token",
            sharedSecret = SecureByteArray("0123456789abcdef".encodeToByteArray()), // 16-byte AES key
        )
        cm.storeCredentials(creds)
        cm.setActiveCredentials(creds)
        return ContactsProvider(HttpClient(engine), cm)
    }

    /** Replays [responses] in order, one per HTTP call, recording each request for assertions. */
    private fun sequencedEngine(responses: List<Pair<HttpStatusCode, String>>): MockEngine {
        var i = 0
        return MockEngine { _ ->
            val (status, body) = responses[i++]
            respond(content = body, status = status, headers = jsonHeaders)
        }
    }

    // ---- single-call mapping ----

    @Test
    fun create_200_returnsOk() = runTest {
        val engine = sequencedEngine(
            listOf(HttpStatusCode.OK to ContactFixtures.okBody(uniqueId, tagA)),
        )
        val result = provider(engine).createContact(ContactContent(odinId = "sam.dotyou.cloud"))

        val ok = assertIs<ContactWriteResult.Ok>(result)
        assertEquals(Uuid.parse(uniqueId), ok.body.uniqueId)
        assertEquals(Uuid.parse(tagA), ok.body.versionTag)
    }

    @Test
    fun create_409_returnsConflictWithTagAndId() = runTest {
        val engine = sequencedEngine(
            listOf(HttpStatusCode.Conflict to ContactFixtures.conflictBody(uniqueId, tagA)),
        )
        val result = provider(engine).createContact(ContactContent(odinId = "sam.dotyou.cloud"))

        val conflict = assertIs<ContactWriteResult.Conflict>(result)
        assertEquals(Uuid.parse(tagA), conflict.conflict.versionTag)
        assertEquals(Uuid.parse(uniqueId), conflict.conflict.uniqueId)
    }

    @Test
    fun create_403_throws() = runTest {
        val engine = sequencedEngine(listOf(HttpStatusCode.Forbidden to "{}"))
        assertFailsWith<ForbiddenException> {
            provider(engine).createContact(ContactContent())
        }
    }

    @Test
    fun update_404_returnsNotFound() = runTest {
        val engine = sequencedEngine(listOf(HttpStatusCode.NotFound to "{}"))
        val result = provider(engine)
            .updateContact(Uuid.parse(uniqueId), ContactContent(), Uuid.parse(tagA))

        assertEquals(ContactWriteResult.NotFound, result)
    }

    @Test
    fun delete_204_true_and_404_false() = runTest {
        assertTrue(
            provider(sequencedEngine(listOf(HttpStatusCode.NoContent to "")))
                .deleteContact(Uuid.parse(uniqueId)),
        )
        assertFalse(
            provider(sequencedEngine(listOf(HttpStatusCode.NotFound to "{}")))
                .deleteContact(Uuid.parse(uniqueId)),
        )
    }

    @Test
    fun sync_accepts202() = runTest {
        var sawSyncPath = false
        val engine = MockEngine { request ->
            sawSyncPath = request.url.encodedPath.endsWith("/contacts/sync/sam.dotyou.cloud")
            respond(content = "", status = HttpStatusCode.Accepted, headers = jsonHeaders)
        }
        provider(engine).syncContact(OdinId("sam.dotyou.cloud"))
        assertTrue(sawSyncPath, "sync should POST to /contacts/sync/{odinId}")
    }

    // ---- saveContact merge-and-retry ----

    @Test
    fun save_createSucceeds_singleCall() = runTest {
        val engine = sequencedEngine(
            listOf(HttpStatusCode.OK to ContactFixtures.okBody(uniqueId, tagA)),
        )
        val response = provider(engine).saveContact(ContactContent(odinId = "sam.dotyou.cloud"))

        assertEquals(Uuid.parse(uniqueId), response.uniqueId)
        assertEquals(Uuid.parse(tagA), response.versionTag)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun save_createConflict_thenUpdateSucceeds() = runTest {
        // CREATE -> 409 (exists), switch to UPDATE -> 200.
        val engine = sequencedEngine(
            listOf(
                HttpStatusCode.Conflict to ContactFixtures.conflictBody(uniqueId, tagA),
                HttpStatusCode.OK to ContactFixtures.okBody(uniqueId, tagB),
            ),
        )
        val response = provider(engine).saveContact(ContactContent(name = ContactName(displayName = "Sam")))

        assertEquals(Uuid.parse(tagB), response.versionTag)
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun save_takesFreshTagOnEachConflict_thenSucceeds() = runTest {
        // CREATE -> 409 (tagA); UPDATE -> 409 (tagB, someone raced); UPDATE -> 200 (tagC).
        val engine = sequencedEngine(
            listOf(
                HttpStatusCode.Conflict to ContactFixtures.conflictBody(uniqueId, tagA),
                HttpStatusCode.Conflict to ContactFixtures.conflictBody(uniqueId, tagB),
                HttpStatusCode.OK to ContactFixtures.okBody(uniqueId, tagC),
            ),
        )
        val response = provider(engine).saveContact(ContactContent(), maxAttempts = 3)

        assertEquals(Uuid.parse(tagC), response.versionTag)
        assertEquals(3, engine.requestHistory.size)
    }

    @Test
    fun save_throwsWhenContentionExceedsMaxAttempts() = runTest {
        // CREATE 409 + every UPDATE 409 -> loop must give up after maxAttempts updates.
        val engine = sequencedEngine(
            List(1 + 2) { HttpStatusCode.Conflict to ContactFixtures.conflictBody(uniqueId, tagA) },
        )
        assertFailsWith<IllegalStateException> {
            provider(engine).saveContact(ContactContent(), maxAttempts = 2)
        }
    }

    @Test
    fun save_updateFirst_whenCallerKnowsContact() = runTest {
        // Known contact -> goes straight to UPDATE, no CREATE call.
        val engine = sequencedEngine(
            listOf(HttpStatusCode.OK to ContactFixtures.okBody(uniqueId, tagB)),
        )
        val response = provider(engine).saveContact(
            content = ContactContent(name = ContactName(displayName = "Sam")),
            knownUniqueId = Uuid.parse(uniqueId),
            knownVersionTag = Uuid.parse(tagA),
        )

        assertEquals(Uuid.parse(tagB), response.versionTag)
        assertEquals(1, engine.requestHistory.size)
        assertTrue(engine.requestHistory.first().url.encodedPath.endsWith("/contacts/$uniqueId"))
    }
}
