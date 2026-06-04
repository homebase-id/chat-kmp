package id.homebase.core.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class FullPayloadByteCacheTest {

    private fun cachedImage(size: Int) = CachedImage(ByteArray(size), "image/jpeg", null)

    // Mirror the production scope: a SupervisorJob so a failing load does not
    // cancel the cache, on the test scheduler so advanceUntilIdle drives it.
    private fun TestScope.newCache(maxBytes: Long) = FullPayloadByteCache(
        maxBytes = maxBytes,
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
    )

    @Test
    fun `cache hit returns stored value without reloading`() = runTest {
        val cache = newCache(10_000)
        var calls = 0
        val loader: suspend () -> CachedImage? = { calls++; cachedImage(100) }

        val first = cache.getOrLoad("k", loader)
        val second = cache.getOrLoad("k", loader)

        assertEquals(100, first?.bytes?.size)
        assertEquals(100, second?.bytes?.size)
        assertEquals(1, calls)
    }

    @Test
    fun `concurrent callers coalesce onto a single load`() = runTest {
        val cache = newCache(10_000)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val loader: suspend () -> CachedImage? = { calls++; gate.await(); cachedImage(100) }

        val d1 = async { cache.getOrLoad("k", loader) }
        val d2 = async { cache.getOrLoad("k", loader) }
        advanceUntilIdle() // both register and park on the in-flight load at the gate

        assertEquals(1, calls)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(100, d1.await()?.bytes?.size)
        assertEquals(100, d2.await()?.bytes?.size)
        assertEquals(1, calls)
    }

    @Test
    fun `evicts least-recently-used when over the byte budget`() = runTest {
        val cache = newCache(100)
        var callsA = 0
        var callsB = 0
        val loadA: suspend () -> CachedImage? = { callsA++; cachedImage(60) }
        val loadB: suspend () -> CachedImage? = { callsB++; cachedImage(60) }

        cache.getOrLoad("A", loadA)   // {A:60}
        cache.getOrLoad("B", loadB)   // 60+60 > 100 -> evict A -> {B:60}
        cache.getOrLoad("B", loadB)   // hit, no reload
        cache.getOrLoad("A", loadA)   // A was evicted -> reload

        assertEquals(2, callsA)
        assertEquals(1, callsB)
    }

    @Test
    fun `null result is not cached`() = runTest {
        val cache = newCache(10_000)
        var calls = 0
        val loader: suspend () -> CachedImage? = { calls++; null }

        assertNull(cache.getOrLoad("k", loader))
        assertNull(cache.getOrLoad("k", loader))
        assertEquals(2, calls)
    }

    @Test
    fun `loader failure drops the in-flight slot so the next call retries`() = runTest {
        val cache = newCache(10_000)
        var calls = 0
        val loader: suspend () -> CachedImage? = {
            calls++
            if (calls == 1) throw IllegalStateException("boom") else cachedImage(50)
        }

        assertFailsWith<IllegalStateException> { cache.getOrLoad("k", loader) }
        val recovered = cache.getOrLoad("k", loader)

        assertEquals(50, recovered?.bytes?.size)
        assertEquals(2, calls)
    }

    @Test
    fun `clear drops cached entries`() = runTest {
        val cache = newCache(10_000)
        var calls = 0
        val loader: suspend () -> CachedImage? = { calls++; cachedImage(50) }

        cache.getOrLoad("k", loader)
        cache.clear()
        cache.getOrLoad("k", loader)

        assertEquals(2, calls)
    }

    @Test
    fun `clear while a load is in flight does not repopulate the cache`() = runTest {
        val cache = newCache(10_000)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val loader: suspend () -> CachedImage? = { calls++; gate.await(); cachedImage(100) }

        // A decrypt is in flight: the load registers and parks at the gate.
        val inFlight = async { cache.getOrLoad("k", loader) }
        advanceUntilIdle()
        assertEquals(1, calls)

        // Logout-style clear runs while the load is still parked.
        cache.clear()

        // The parked load now finishes. Its bytes belong to the cleared epoch,
        // so they must NOT be written back — but the awaiter still gets them.
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(100, inFlight.await()?.bytes?.size)

        // A fresh request reloads, proving the stale load did not repopulate.
        cache.getOrLoad("k") { calls++; cachedImage(100) }
        assertEquals(2, calls)
    }

    @Test
    fun `entry larger than the whole budget is served but not retained`() = runTest {
        val cache = newCache(100)
        var calls = 0
        val loader: suspend () -> CachedImage? = { calls++; cachedImage(500) }

        val first = cache.getOrLoad("big", loader)
        val second = cache.getOrLoad("big", loader)

        assertEquals(500, first?.bytes?.size)
        assertEquals(500, second?.bytes?.size)
        assertEquals(2, calls) // not retained (too big), so reloaded
    }
}
