package id.homebase.core.notifications

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TokenCatchUpTest {
    @Test
    fun aTokenDeliveredBeforeTheListenerAttachedIsRegistered() = runTest {
        val registered = mutableListOf<String>()
        registerTokenDeliveredEarlier({ "fcm" }) { registered += it }
        assertEquals(listOf("fcm"), registered)
    }

    @Test
    fun noTokenYetRegistersNothing() = runTest {
        val registered = mutableListOf<String>()
        registerTokenDeliveredEarlier({ null }) { registered += it }
        assertEquals(emptyList(), registered)
    }

    @Test
    fun aFailingTokenFetchRegistersNothingAndDoesNotThrow() = runTest {
        val registered = mutableListOf<String>()
        registerTokenDeliveredEarlier({ error("keychain locked") }) { registered += it }
        assertEquals(emptyList(), registered)
    }
}
