@file:OptIn(ExperimentalEncodingApi::class)

package id.homebase.core.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import okio.Buffer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins how Coil 3 resolves a [Keyer] for an http(s) String model — the contract the
 * avatar memory-cache key depends on.
 */
class CoilKeyerResolutionTest {

    private val url = "https://frodo.digital/pub/image"

    @Test
    fun keyerForUri_isPolledForHttpStringModel() {
        val log = mutableListOf<String>()
        val loader = loader(
            keyers = listOf(
                RecordingKeyer<Uri>("Keyer<Uri>", log) { null } to Uri::class,
                RecordingKeyer<Any>("Keyer<Any>", log) { null } to Any::class,
            ),
        )

        runBlocking { loader.execute(request(loader)) }

        assertTrue(
            log.any { it.startsWith("Keyer<Uri>") },
            "Keyer<Uri> was never polled for an http String model. Polled: $log",
        )
        assertTrue(
            log.all { it.contains("|data=coil3.Uri|") },
            "Keyer saw something other than a mapped coil3.Uri. Polled: $log",
        )
    }

    @Test
    fun keyerForUri_ownsTheMemoryCacheKeyAndGatesRefetch() {
        var revision = 1L
        var fetches = 0
        val loader = loader(
            keyers = listOf(
                RecordingKeyer<Uri>("k", mutableListOf()) { "avatar|$it|v=$revision" } to Uri::class,
            ),
            onFetch = { fetches++ },
        )

        val first = runBlocking { loader.execute(request(loader)) }
        assertIs<SuccessResult>(first)
        assertEquals("avatar|$url|v=1", first.memoryCacheKey?.key)
        assertEquals(1, fetches)

        val second = runBlocking { loader.execute(request(loader)) }
        assertIs<SuccessResult>(second)
        assertEquals(DataSource.MEMORY_CACHE, second.dataSource)
        assertEquals(1, fetches)

        revision = 2L
        val third = runBlocking { loader.execute(request(loader)) }
        assertIs<SuccessResult>(third)
        assertEquals("avatar|$url|v=2", third.memoryCacheKey?.key)
        assertEquals(2, fetches)
    }

    private fun request(loader: ImageLoader) = ImageRequest.Builder(PlatformContext.INSTANCE)
        .data(url)
        .build()
        .also { check(loader.memoryCache != null) { "memory cache disabled" } }

    private fun loader(
        keyers: List<Pair<Keyer<*>, KClass<*>>>,
        onFetch: () -> Unit = {},
    ): ImageLoader = ImageLoader.Builder(PlatformContext.INSTANCE)
        .components {
            keyers.forEach { (keyer, type) ->
                @Suppress("UNCHECKED_CAST")
                add(keyer as Keyer<Any>, type as KClass<Any>)
            }
            add(StubPngFetcher.Factory(onFetch))
        }
        .diskCache(null)
        .build()
}

private class RecordingKeyer<T : Any>(
    private val label: String,
    private val log: MutableList<String>,
    private val key: (T) -> String?,
) : Keyer<T> {
    override fun key(data: T, options: Options): String? {
        log += "$label|data=${data::class.qualifiedName}|$data"
        return key(data)
    }
}

private class StubPngFetcher(
    private val options: Options,
    private val onFetch: () -> Unit,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        onFetch()
        return SourceFetchResult(
            source = ImageSource(Buffer().write(PNG_2X2), options.fileSystem),
            mimeType = "image/png",
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val onFetch: () -> Unit) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher =
            StubPngFetcher(options, onFetch)
    }
}

private val PNG_2X2: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR4nGP4z8DwH4QZYAwAR8oH+WdZbrcAAAAASUVORK5CYII=",
)
