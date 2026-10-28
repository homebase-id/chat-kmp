package id.homebase.core.notifications

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PushHandoverTest {
    private val replaced = ApiCredentials.create(OdinId("frodo.example.com"), "old-token", SecureByteArray(ByteArray(16) { 3 }))

    private class Steps(
        override val hasPush: Boolean = true,
        private val token: String? = "fcm",
        private val failing: Set<String> = emptySet(),
    ) : PushHandoverSteps {
        val calls = mutableListOf<String>()
        private fun call(name: String) {
            calls += name
            if (name in failing) error("$name failed")
        }
        override suspend fun pushToken() = token
        override suspend fun subscribeCurrent(token: String) = call("subscribe current")
        override suspend fun unsubscribeCurrent() = call("unsubscribe current")
        override suspend fun unsubscribeReplaced(replaced: ApiCredentials) = call("unsubscribe replaced")
        override suspend fun deleteReplaced(replaced: ApiCredentials) = call("delete replaced")
    }

    @Test
    fun pushMovesToTheNewSignInThenTheOldOneGoes() = runTest {
        val steps = Steps()
        handOverPush(replaced, steps, retryDelayMs = 0)
        assertEquals(listOf("subscribe current", "unsubscribe replaced", "delete replaced"), steps.calls)
    }

    @Test
    fun ifTheNewSignInCantSubscribePushStaysOnTheOldOne() = runTest {
        val steps = Steps(failing = setOf("subscribe current"))
        handOverPush(replaced, steps, retryDelayMs = 0)
        assertEquals(List(3) { "subscribe current" }, steps.calls)
    }

    @Test
    fun ifTheOldSignInCantUnsubscribeTheNewSubscriptionIsUndone() = runTest {
        val steps = Steps(failing = setOf("unsubscribe replaced"))
        handOverPush(replaced, steps, retryDelayMs = 0)
        assertEquals(listOf("subscribe current") + List(3) { "unsubscribe replaced" } + "unsubscribe current", steps.calls)
    }

    @Test
    fun withoutPushTheOldSignInIsJustDeleted() = runTest {
        val steps = Steps(hasPush = false)
        handOverPush(replaced, steps, retryDelayMs = 0)
        assertEquals(listOf("delete replaced"), steps.calls)
    }

    @Test
    fun webPushStaysWithTheOldSignIn() = runTest {
        val steps = Steps(token = null)
        handOverPush(replaced, steps, retryDelayMs = 0)
        assertEquals(emptyList(), steps.calls)
    }
}
