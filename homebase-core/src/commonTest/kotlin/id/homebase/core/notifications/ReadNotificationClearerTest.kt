package id.homebase.core.notifications

import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.convo.ConversationsData
import id.homebase.chat.services.convo.EnrichmentState
import id.homebase.core.avatars.ConversationAvatarModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)
class ReadNotificationClearerTest {
    private val a = Uuid.random()
    private val b = Uuid.random()

    private fun convo(id: Uuid, unread: Int) = ConversationUiModel(
        id = id,
        name = "",
        lastMessage = "",
        latestMessageTimestamp = Instant.fromEpochMilliseconds(0),
        avatarInitials = "",
        avatarTiny = null,
        unreadCount = unread,
        lastRead = Instant.fromEpochMilliseconds(0),
        avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
        admins = emptySet(),
    )

    private fun data(vararg items: Pair<Uuid, Int>, enriched: Boolean = true) = ConversationsData(
        items = items.map { convo(it.first, it.second) },
        enrichment = EnrichmentState(hasUnreadCounts = enriched),
    )

    private fun TestScope.run(flow: MutableStateFlow<ConversationsData>, cleared: MutableList<String>) {
        val clearer = ReadNotificationClearer(flow) { cleared += it }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { clearer.run() }
    }

    @Test
    fun unreadDroppingToZeroClearsOnceWithoutSelectionChange() = runTest {
        val flow = MutableStateFlow(data(a to 3))
        val cleared = mutableListOf<String>()
        run(flow, cleared)
        flow.value = data(a to 0)
        flow.value = data(a to 0, b to 0)
        assertEquals(listOf(a.toString()), cleared)
    }

    @Test
    fun unselectedChatGoingTwoToZeroClears() = runTest {
        val flow = MutableStateFlow(data(a to 1, b to 2))
        val cleared = mutableListOf<String>()
        run(flow, cleared)
        flow.value = data(a to 1, b to 0)
        assertEquals(listOf(b.toString()), cleared)
    }

    @Test
    fun otherTransitionsClearNothing() = runTest {
        val flow = MutableStateFlow(data(a to 0, b to 3))
        val cleared = mutableListOf<String>()
        run(flow, cleared)
        flow.value = data(a to 0, b to 1)
        flow.value = data(a to 2, b to 1)
        flow.value = data(a to 2)
        assertEquals(emptyList(), cleared)
    }

    @Test
    fun firstEnrichedEmissionOnlySeeds() = runTest {
        val flow = MutableStateFlow(data(a to 0, enriched = false))
        val cleared = mutableListOf<String>()
        run(flow, cleared)
        flow.value = data(a to 0)
        assertEquals(emptyList(), cleared)
    }

    @Test
    fun basicOnlyEmissionIsNotReadAsEveryChatBeingRead() = runTest {
        val flow = MutableStateFlow(data(a to 3))
        val cleared = mutableListOf<String>()
        run(flow, cleared)
        flow.value = data(a to 0, enriched = false)
        flow.value = data(a to 3)
        assertEquals(emptyList(), cleared)
    }
}
