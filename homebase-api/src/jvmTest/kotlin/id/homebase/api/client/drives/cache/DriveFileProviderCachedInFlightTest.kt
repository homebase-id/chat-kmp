package id.homebase.api.client.drives.cache

import id.homebase.api.client.ByteApiResponse
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.Headers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DriveFileProviderCachedInFlightTest {

    private val httpClient = HttpClient(MockEngine { respond(ByteArray(0)) })
    private var tempDir = ""
    private lateinit var provider: DriveFileProviderCached

    private val bytes = ByteArray(32) { it.toByte() }
    private fun response(b: ByteArray = bytes) = ByteApiResponse(200, Headers.Empty, b, "image/webp")

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("hb-inflight-test").toString()
        provider = DriveFileProviderCached(
            httpClient = httpClient,
            credentialsManager = CredentialsManager(),
            fileOperationsProvider = object : FileOperationsProvider {
                override fun getCacheDirectory() = tempDir
                override fun openFileInput(path: String): InputProvider = error("not used")
                override suspend fun readFileBytes(path: String): ByteArray = error("not used")
                override fun deleteTempFile(path: String) = false
                override fun getFileSize(path: String) = 0L
                override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
                override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
                override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used")
            }
        )
    }

    @AfterTest
    fun tearDown() {
        httpClient.close()
        File(tempDir).deleteRecursively()
    }

    @Test
    fun `concurrent reads of one key share a single fetch`() = runBlocking {
        withTimeout(10_000) {
            val fetches = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val callers = List(5) {
                async(Dispatchers.Default) {
                    provider.readPayloadThrough("k") {
                        fetches.incrementAndGet()
                        started.complete(Unit)
                        gate.await()
                        response()
                    }
                }
            }
            started.await()
            gate.complete(Unit)

            callers.awaitAll().forEach { assertContentEquals(bytes, it.bytes) }
            assertEquals(1, fetches.get())
            assertEquals(0, provider.inFlightCount())
        }
    }

    @Test
    fun `cancelling the first caller does not cancel the fetch and the result is cached`() = runBlocking {
        withTimeout(10_000) {
            val fetches = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val fetch: suspend () -> ByteApiResponse = {
                fetches.incrementAndGet()
                started.complete(Unit)
                gate.await()
                response()
            }
            val first = launch(Dispatchers.Default) { provider.readPayloadThrough("k", fetch) }
            started.await()
            first.cancelAndJoin()
            val second = async(Dispatchers.Default) { provider.readPayloadThrough("k", fetch) }
            gate.complete(Unit)

            assertContentEquals(bytes, second.await().bytes)
            assertContentEquals(bytes, provider.readPayloadThrough("k", fetch).bytes)
            assertEquals(1, fetches.get())
        }
    }

    @Test
    fun `clear during an in-flight fetch that ignores cancellation writes nothing`() = runBlocking {
        withTimeout(10_000) {
            val fetches = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val stale = launch(Dispatchers.Default) {
                provider.readPayloadThrough("k") {
                    fetches.incrementAndGet()
                    started.complete(Unit)
                    withContext(NonCancellable) { gate.await() }
                    response()
                }
            }
            started.await()
            stale.cancelAndJoin()
            provider.clearCaches()
            gate.complete(Unit)

            // Same payload semaphore: this read only gets its permit after the stale fetch is done.
            provider.readPayloadThrough("other") { response() }
            provider.readPayloadThrough("k") { fetches.incrementAndGet(); response() }

            assertEquals(2, fetches.get())
        }
    }

    @Test
    fun `a waiting caller refetches after clear cancels the in-flight fetch`() = runBlocking {
        withTimeout(10_000) {
            val fetches = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val never = CompletableDeferred<Unit>()
            val fresh = ByteArray(8) { 9 }
            val caller = async(Dispatchers.Default) {
                provider.readPayloadThrough("k") {
                    if (fetches.incrementAndGet() == 1) {
                        started.complete(Unit)
                        never.await()
                    }
                    response(fresh)
                }
            }
            started.await()
            provider.clearCaches()

            assertContentEquals(fresh, caller.await().bytes)
            assertEquals(2, fetches.get())
            assertEquals(0, provider.inFlightCount())
        }
    }

    @Test
    fun `a CancellationException thrown by the fetch propagates and is not cached`() = runBlocking {
        withTimeout(10_000) {
            val fetches = AtomicInteger()
            assertFailsWith<CancellationException> {
                provider.readPayloadThrough("k") {
                    fetches.incrementAndGet()
                    throw CancellationException("fetch cancelled")
                }
            }
            assertEquals(0, provider.inFlightCount())

            val next = provider.readPayloadThrough("k") { fetches.incrementAndGet(); response() }
            assertContentEquals(bytes, next.bytes)
            assertEquals(2, fetches.get())
        }
    }

    @Test
    fun `in-flight map is empty after success and after failure`() = runBlocking {
        withTimeout(10_000) {
            provider.readPayloadThrough("ok") { response() }
            assertFailsWith<IllegalStateException> { provider.readPayloadThrough("bad") { error("boom") } }
            assertEquals(0, provider.inFlightCount())
        }
    }
}
