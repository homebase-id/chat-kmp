@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.ClientException
import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Covers the per-app contact app-data client: the wire contract of the four [ContactsProvider]
 * writes (shared body, tier paths, version-gated retry, size-cap 400), the pure read/normalization
 * helpers, and the [ContactRepository] bulk read + size-cap translation.
 */
class ContactAppDataTest {

    private val testDomain = OdinId("test.homebase.id")
    private val secretBytes = "0123456789abcdef".encodeToByteArray()
    private val uniqueId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val tagA = Uuid.parse("22222222-2222-2222-2222-222222222222")
    private val tagB = Uuid.parse("33333333-3333-3333-3333-333333333333")

    // appId in both forms — the registration constant is dashless, the map key is hyphenated.
    private val appIdHyphenated = "2d781401-3804-4b57-b4aa-d8e4e2ef39f4"
    private val appIdDashless = "2d78140138044b57b4aad8e4e2ef39f4"

    private val jsonHeaders =
        headersOf("Content-Type" to listOf(ContentType.Application.Json.toString()))

    private suspend fun provider(engine: MockEngine): ContactsProvider {
        val cm = CredentialsManager()
        val creds = ApiCredentials.create(
            domain = testDomain,
            clientAccessToken = "test-token",
            sharedSecret = SecureByteArray(secretBytes),
        )
        cm.storeCredentials(creds)
        cm.setActiveCredentials(creds)
        return ContactsProvider(HttpClient(engine), cm, { _, _ -> null })
    }

    /** Decrypts the shared-secret transport envelope back to the plaintext app-data request. */
    private suspend fun decryptRequest(envelope: String): SetContactAppDataRequest =
        OdinSystemSerializer.deserialize(CryptoHelper.decryptContentAsString(envelope, secretBytes))

    // ------------------------------------------------------------
    // Models / read helpers
    // ------------------------------------------------------------

    @Test
    fun requestSerializes_contentAndVersionTag_neverAppId() {
        val json = OdinSystemSerializer.serialize(SetContactAppDataRequest("payload", tagA))
        assertTrue(json.contains("\"content\":\"payload\""), json)
        assertTrue(json.contains("\"versionTag\":\"$tagA\""), json)
        assertFalse(json.contains("appId"), "appId is stamped server-side, never sent: $json")
    }

    @Test
    fun appDataFor_readsByCanonicalKey_acceptingEitherAppIdForm() {
        // Stored map is keyed hyphenated (the server's canonical form); lookups by either form hit.
        val content = ContactContent(appData = mapOf(appIdHyphenated to "v"))
        assertEquals("v", content.appDataFor(appIdHyphenated))
        assertEquals("v", content.appDataFor(appIdDashless))
        assertNull(content.appDataFor("99999999-9999-9999-9999-999999999999"))
        assertNull(ContactContent().appDataFor(appIdHyphenated)) // absent → null
    }

    @Test
    fun toCanonicalAppId_normalizesBothFormsToHyphenatedLowercase() {
        assertEquals(appIdHyphenated, appIdDashless.toCanonicalAppId())
        assertEquals(appIdHyphenated, appIdHyphenated.uppercase().toCanonicalAppId())
    }

    @Test
    fun contactAppExtData_parsesAppDataMap() {
        val ext = OdinSystemSerializer.deserialize<ContactAppExtData>(
            """{"appData":{"$appIdHyphenated":"bulk-value"}}""",
        )
        assertEquals("bulk-value", ext.appData[appIdHyphenated])
    }

    // ------------------------------------------------------------
    // Provider wire contract
    // ------------------------------------------------------------

    @Test
    fun setContactAppData_putsInlinePath_sendsContentAndTag_returnsNewTag() = runTest {
        var envelope: String? = null
        val engine = MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/contacts/$uniqueId/app-data"), request.url.toString())
            envelope = (request.body as TextContent).text
            respond(ContactFixtures.okBody("$uniqueId", "$tagB"), HttpStatusCode.OK, jsonHeaders)
        }

        val result = provider(engine).setContactAppData(uniqueId, "hello", tagA)

        assertEquals(tagB, assertIs<ContactWriteResult.Ok>(result).body.versionTag)
        val sent = decryptRequest(envelope!!)
        assertEquals("hello", sent.content)
        assertEquals(tagA, sent.versionTag)
    }

    @Test
    fun setContactAppExtData_putsBulkPath() = runTest {
        val engine = MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/contacts/$uniqueId/app-ext-data"), request.url.toString())
            respond(ContactFixtures.okBody("$uniqueId", "$tagB"), HttpStatusCode.OK, jsonHeaders)
        }
        assertIs<ContactWriteResult.Ok>(provider(engine).setContactAppExtData(uniqueId, "big", tagA))
    }

    @Test
    fun deleteContactAppData_sendsVersionTagQuery() = runTest {
        val engine = MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/contacts/$uniqueId/app-data"), request.url.toString())
            assertTrue(request.url.encodedQuery.contains("versionTag=$tagA"), request.url.toString())
            respond(ContactFixtures.okBody("$uniqueId", "$tagB"), HttpStatusCode.OK, jsonHeaders)
        }
        assertEquals(tagB, assertIs<ContactWriteResult.Ok>(provider(engine).deleteContactAppData(uniqueId, tagA)).body.versionTag)
    }

    @Test
    fun setContactAppData_404_returnsNotFound() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.NotFound, jsonHeaders) }
        assertEquals(ContactWriteResult.NotFound, provider(engine).setContactAppData(uniqueId, "x", tagA))
    }

    @Test
    fun setContactAppData_409_retriesWithFreshTag() = runTest {
        val tags = mutableListOf<Uuid>()
        val responses = listOf(
            HttpStatusCode.Conflict to ContactFixtures.conflictBody("$uniqueId", "$tagB"),
            HttpStatusCode.OK to ContactFixtures.okBody("$uniqueId", "$tagB"),
        )
        var i = 0
        val engine = MockEngine { request ->
            tags += decryptRequest((request.body as TextContent).text).versionTag
            val (status, body) = responses[i++]
            respond(body, status, jsonHeaders)
        }

        assertIs<ContactWriteResult.Ok>(provider(engine).setContactAppData(uniqueId, "x", tagA))
        assertEquals(listOf(tagA, tagB), tags) // first attempt last-seen tag, retry with authoritative tag
    }

    @Test
    fun setContactAppData_tooLarge_throwsMaxContentLength() = runTest {
        val engine = MockEngine {
            respond(
                ContactFixtures.problemBody(OdinClientErrorCode.MaxContentLengthExceeded.value),
                HttpStatusCode.BadRequest,
                jsonHeaders,
            )
        }
        val ex = assertFailsWith<ClientException> { provider(engine).setContactAppData(uniqueId, "toobig", tagA) }
        assertEquals(OdinClientErrorCode.MaxContentLengthExceeded, ex.errorCode)
    }

    // ------------------------------------------------------------
    // Repository: bulk read + size-cap translation
    // ------------------------------------------------------------

    private suspend fun TestScope.repo(
        engine: MockEngine,
        payloadReader: ContactPayloadReader = ContactPayloadReader { _, _, _, _ -> null },
    ): ContactRepository {
        val cm = CredentialsManager()
        val creds = ApiCredentials.create(
            domain = testDomain,
            clientAccessToken = "test-token",
            sharedSecret = SecureByteArray(secretBytes),
        )
        cm.storeCredentials(creds)
        cm.setActiveCredentials(creds)
        val provider = ContactsProvider(HttpClient(engine), cm, { _, _ -> null })
        val dbm = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })
        val scope = backgroundScope + UnconfinedTestDispatcher(testScheduler)
        return ContactRepository(provider, payloadReader, dbm, cm, EventBus(), scope)
    }

    /** A Contact carrying the file id + key needed to fetch its bulk payload. */
    private fun bulkContact(hasPayload: Boolean) = Contact(
        uniqueId = uniqueId,
        versionTag = tagA,
        content = ContactContent(),
        fileId = Uuid.parse("99999999-9999-9999-9999-999999999999"),
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        payloadKeys =
            if (hasPayload) setOf(ContactsProvider.CONTACT_APP_EXT_DATA_PAYLOAD_KEY) else emptySet(),
    )

    private val okEngine get() = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }

    @Test
    fun loadAppExtData_fetchesAppExtDataKey_decodesAppIdValue() = runTest {
        val reader = ContactPayloadReader { _, _, key, _ ->
            assertEquals(ContactsProvider.CONTACT_APP_EXT_DATA_PAYLOAD_KEY, key)
            """{"appData":{"$appIdHyphenated":"bulk"}}""".encodeToByteArray()
        }
        // appId given dashless; the lookup normalizes to the canonical hyphenated map key.
        assertEquals("bulk", repo(okEngine, reader).loadAppExtData(bulkContact(true), appIdDashless))
    }

    @Test
    fun loadAppExtData_skipsFetch_whenPayloadAbsent() = runTest {
        var fetched = false
        val reader = ContactPayloadReader { _, _, _, _ -> fetched = true; null }
        assertNull(repo(okEngine, reader).loadAppExtData(bulkContact(false), appIdHyphenated))
        assertFalse(fetched, "no payload key → no fetch")
    }

    @Test
    fun setAppData_tooLarge_translatesToTooLargeException() = runTest {
        val engine = MockEngine {
            respond(
                ContactFixtures.problemBody(OdinClientErrorCode.MaxContentLengthExceeded.value),
                HttpStatusCode.BadRequest,
                jsonHeaders,
            )
        }
        assertFailsWith<ContactAppDataTooLargeException> {
            repo(engine).setAppData(uniqueId, appIdHyphenated, "big", tagA)
        }
    }
}
