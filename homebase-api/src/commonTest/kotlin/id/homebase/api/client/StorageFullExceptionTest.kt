package id.homebase.api.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StorageFullExceptionTest {
    @Test
    fun retryAfterIsReadAsSeconds() {
        assertEquals(3600.seconds, retryAfterOrNull("3600"))
        assertEquals(60.seconds, retryAfterOrNull(" 60 "))
        assertNull(retryAfterOrNull(null))
        assertNull(retryAfterOrNull("0"))
        assertNull(retryAfterOrNull("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun storageFullIsFoundThroughTheCauseChain() {
        val full = StorageFullException(retryAfter = null, problem = null)
        assertTrue(full.isStorageFull())
        assertTrue(RuntimeException("wrapped", full).isStorageFull())
        assertFalse(ServerException(500, null, null).isStorageFull())
        assertEquals(507, full.status)
    }

    @Test
    fun retryHelperDoesNotRetryStorageFull() = kotlinx.coroutines.test.runTest {
        var calls = 0
        val result = withRetryResult(RetryConfig(maxRetries = 3, initialDelayMs = 1), "test") {
            calls++
            throw StorageFullException(retryAfter = null, problem = null)
        }
        assertEquals(1, calls)
        assertTrue(result is RetryResult.Failure)
    }
}
