package id.homebase.core.notifications

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.contacts.ContactInfoGateway
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.notifications.PushNotificationApi
import id.homebase.api.youauth.YouAuthState
import id.homebase.core.settings.InMemorySettings
import id.homebase.core.settings.UserPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationServiceVoipTokenTest {

    private class ThrowingBackend : NotificationBackend {
        var pushTokenCalls = 0
        override fun addListener(listener: NotificationListener) = Unit
        override fun setLogger(log: (String) -> Unit) = Unit
        override fun showLocalNotification(id: Int, title: String, body: String, payloadData: Map<String, String>) = Unit
        override suspend fun getPushToken(): String? {
            pushTokenCalls++
            error("no FCM token")
        }
        override suspend fun deletePushToken() = Unit
    }

    @Test
    fun onVoipToken_whenPushTokenFetchThrows_doesNotThrowOrRegister() = runTest {
        val escaped = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            UnconfinedTestDispatcher(testScheduler) + Job() + CoroutineExceptionHandler { _, e -> escaped += e },
        )
        var requests = 0
        val httpClient = HttpClient(MockEngine { requests++; respondOk() })
        val backend = ThrowingBackend()
        val service = NotificationService(
            api = PushNotificationApi(httpClient, CredentialsManager()),
            scope = scope,
            // never touched by onVoipToken; its constructor is internal to homebase-api
            contactInfo = allocateWithoutConstructor(),
            userPreferences = UserPreferences(InMemorySettings()),
            credentialsManager = CredentialsManager(),
            pendingNotificationTap = PendingNotificationTap(scope = scope),
            notificationBackend = backend,
            eventBus = EventBus(),
            authState = MutableStateFlow(YouAuthState.Unauthenticated),
            replacedSignIns = emptyFlow(),
            httpClient = httpClient,
        )

        val callsAfterInit = backend.pushTokenCalls

        service.onVoipToken("voip-token")

        assertEquals(callsAfterInit + 1, backend.pushTokenCalls)
        assertTrue(escaped.isEmpty(), "push token failure escaped into the scope: $escaped")
        assertEquals(0, requests)
        scope.coroutineContext[Job]?.cancel()
    }

    private fun allocateWithoutConstructor(): ContactInfoGateway {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, ContactInfoGateway::class.java) as ContactInfoGateway
    }
}
