package id.homebase.core.share

import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.core.di.allModules
import id.homebase.core.moments.services.MomentCreateFlowState
import id.homebase.core.notifications.AppNavigator
import id.homebase.core.notifications.NotificationNavigationEvent
import id.homebase.core.notifications.NotificationNavigationEvent.OpenConversation.Source
import id.homebase.core.session.IdentitySessionScope
import id.homebase.core.session.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.dsl.koinApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncomingLinksTest {

    private class ChannelNavigator : AppNavigator {
        val events = Channel<NotificationNavigationEvent>(Channel.BUFFERED)
        override fun navigateToConversation(conversationId: String, source: Source) {
            events.trySend(NotificationNavigationEvent.OpenConversation(conversationId, source))
        }
        override fun navigateToMomentCompose() {
            events.trySend(NotificationNavigationEvent.OpenMomentCompose)
        }
        override fun navigateToWebDropCompose() {
            events.trySend(NotificationNavigationEvent.OpenWebDropCompose)
        }
    }

    private val navigator = ChannelNavigator()
    private val storage = ShareCacheStorage()
    private val processor = ShareContentProcessor(storage)
    private val eventBus = EventBus()
    private val koin = koinApplication(createEagerInstances = false) { modules(allModules) }.koin
    private val session = IdentitySessionScope(koin)
    private val links = IncomingLinks(navigator, processor, eventBus, session, CoroutineScope(Dispatchers.Unconfined))

    private val a = "11111111-1111-1111-1111-111111111111"
    private val b = "22222222-2222-2222-2222-222222222222"

    @BeforeTest
    fun setUp() = storage.clearSharedContent()

    @AfterTest
    fun tearDown() {
        storage.clearSharedContent()
        koin.close()
    }

    private suspend fun next() = navigator.events.receiveAsFlow().first()
    private suspend fun nothingMore() = withTimeoutOrNull(100) { navigator.events.receive() }

    private fun stageMoment() {
        val relative = "s1/0/photo.jpg"
        java.io.File(processor.resolveFilePath(relative)).apply { parentFile.mkdirs() }.writeText("x")
        processor.writeContent(
            SharedContentDescriptor(
                contentType = SharedContentType.FILE,
                text = "holiday",
                fileNames = listOf(relative),
                mimeTypes = listOf("image/jpeg"),
            )
        )
    }

    @Test
    fun shareLinkHandledBeforeAnyCollectorOpensTheConversationOnce() = runBlocking<Unit> {
        assertTrue(links.handle("homebase-share://send?conversationIds=$a"))
        assertTrue(links.handle("homebase-share-dev://send?conversationIds=$a"))

        assertEquals(NotificationNavigationEvent.OpenConversation(a, Source.ShareIntent), next())
        assertEquals(NotificationNavigationEvent.OpenConversation(a, Source.ShareIntent), next())
        assertNull(nothingMore())
    }

    @Test
    fun androidConversationLinkGivesTheSameEvent() = runBlocking<Unit> {
        links.handle("homebase-fchat://conversation/$a", Source.ShareIntent)
        assertEquals(NotificationNavigationEvent.OpenConversation(a, Source.ShareIntent), next())
    }

    @Test
    fun conversationLinkKeepsTheSourceItWasGiven() = runBlocking<Unit> {
        links.handle("homebase-fchat://conversation/$a", Source.NotificationTap)
        assertEquals(NotificationNavigationEvent.OpenConversation(a, Source.NotificationTap), next())
    }

    @Test
    fun multiChatShareOpensTheFirstConversation() = runBlocking<Unit> {
        links.handle("homebase-share-dev://send?conversationIds=$a,$b")
        assertEquals(NotificationNavigationEvent.OpenConversation(a, Source.ShareIntent), next())
        assertNull(nothingMore())
    }

    @Test
    fun momentShareWithOpenSessionSeedsTheDraftAndQueuesOneCompose() = runBlocking<Unit> {
        session.open("frodo.dotyou.cloud")
        stageMoment()

        links.handle("homebase-share://moment")

        val draft = session.get<MomentCreateFlowState>().draft.value
        assertNotNull(draft)
        assertEquals(1, draft.attachments.size)
        assertEquals("holiday", draft.description)
        assertEquals(NotificationNavigationEvent.OpenMomentCompose, next())
        assertNull(nothingMore())
    }

    @Test
    fun momentShareBeforeTheSessionOpensWaitsForIt() = runBlocking<Unit> {
        stageMoment()

        links.handle("homebase-share://moment")
        assertNull(nothingMore())

        session.open("frodo.dotyou.cloud")

        assertNotNull(session.get<MomentCreateFlowState>().draft.value)
        assertEquals(NotificationNavigationEvent.OpenMomentCompose, next())
    }

    @Test
    fun momentShareWithoutMediaQueuesNothing() = runBlocking<Unit> {
        session.open("frodo.dotyou.cloud")
        processor.writeContent(SharedContentDescriptor(contentType = SharedContentType.TEXT, text = "hi"))

        links.handle("homebase-share://moment")

        assertNull(nothingMore())
    }

    @Test
    fun composeLinksQueueTheirEvents() = runBlocking<Unit> {
        links.handle("homebase-fchat://moment-compose")
        links.handle("homebase-fchat://web-drop-compose")
        assertEquals(NotificationNavigationEvent.OpenMomentCompose, next())
        assertEquals(NotificationNavigationEvent.OpenWebDropCompose, next())
    }

    @Test
    fun callbacksEmitTheEventsAppViewModelUsedTo() = runBlocking<Unit> {
        val url = "homebase-fchat://permission-callback?status=canceled&for=vault"
        links.handle(url)
        assertEquals(
            id.homebase.api.youauth.PermissionCallback.parse(url).event,
            withTimeoutOrNull(1000) { eventBus.events.first() },
        )

        links.handle("homebase-fchat://data-upgrade-callback")
        assertEquals(BackendEvent.DataUpgradeReturned, withTimeoutOrNull(1000) { eventBus.events.first() })
    }

    @Test
    fun oneHandleGivesOneEvent() = runBlocking<Unit> {
        links.handle("homebase-share://send?conversationIds=$a")
        next()
        assertNull(nothingMore())
    }

    @Test
    fun linksItDoesNotOwnAreRefused() {
        assertFalse(links.handle("homebase-fchat://create-account-callback?domain=x.dotyou.cloud"))
        assertFalse(links.handle("homebase-fchat://unknown"))
        assertFalse(links.handle("file:///tmp/a.pdf"))
        assertFalse(links.handle("homebase-share://send?conversationIds="))
        assertFalse(links.handle("homebase-share://send?conversationIds=,"))
        assertFalse(links.handle("https://example.com"))
        assertNull(IncomingLinks.parse("homebase-fchat://auth-callback?code=1"))
    }
}
