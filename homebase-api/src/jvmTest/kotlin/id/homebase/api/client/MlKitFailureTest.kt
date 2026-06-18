package id.homebase.api.client

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the [isMlKitTeardownFailure] predicate the platform uncaught-exception handlers use
 * to keep an ML Kit / MediaPipe background-removal failure from killing the app.
 */
class MlKitFailureTest {

    // qualifiedName of this class contains "mediapipe" (case-insensitive), standing in for a
    // real com.google.mediapipe.* exception we can't instantiate here.
    private class MediaPipeNativeException(message: String? = null) : RuntimeException(message)

    @Test
    fun mediaPipeExceptionClass_isMatch() {
        assertTrue(MediaPipeNativeException().isMlKitTeardownFailure())
    }

    @Test
    fun mediaPipeInMessage_isMatch() {
        assertTrue(RuntimeException("CalculatorGraph::Run failed in mediapipe pipeline").isMlKitTeardownFailure())
    }

    @Test
    fun wrappedCause_isMatch() {
        val wrapped = IllegalStateException("teardown", MediaPipeNativeException("graph closed"))
        assertTrue(wrapped.isMlKitTeardownFailure())
    }

    @Test
    fun unrelatedExceptions_areNotMatched() {
        assertFalse(IllegalStateException("some app bug").isMlKitTeardownFailure())
        assertFalse(NullPointerException().isMlKitTeardownFailure())
        assertFalse(RuntimeException("network timeout").isMlKitTeardownFailure())
    }

    @Test
    fun cyclicCauseChain_terminates() {
        val a = RuntimeException("a")
        val b = RuntimeException("b")
        a.initCause(b)
        b.initCause(a)
        // No mediapipe/mlkit marker anywhere; the important thing is it returns (doesn't loop).
        assertFalse(a.isMlKitTeardownFailure())
    }
}
