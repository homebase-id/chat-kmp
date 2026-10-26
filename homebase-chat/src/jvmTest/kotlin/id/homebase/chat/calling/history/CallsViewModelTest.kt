@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.calling.history

import androidx.lifecycle.viewModelScope
import id.homebase.api.common.OdinId
import id.homebase.chat.calling.CallLogDirection
import id.homebase.chat.calling.CallOutcome
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.services.SeedableConversationLookup
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallSession
import id.homebase.chat.services.calling.CallState
import id.homebase.chat.services.calling.CallVideos
import id.homebase.chat.services.calling.history.CallHistoryEntry
import id.homebase.chat.services.calling.history.CallHistorySource
import id.homebase.chat.services.convo.contact.ContactConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CallsViewModelTest {
    private val alice = OdinId("alice.test")
    private val ist = TimeZone.of("Asia/Kolkata")

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeHistory(initial: List<CallHistoryEntry>) : CallHistorySource {
        override val entries = MutableStateFlow(initial)
        override val hasMore = MutableStateFlow(false)
        override val isLoading = MutableStateFlow(false)
        override val missedCount = MutableStateFlow(0)
        var seen = 0
        var loadedMore = 0
        override suspend fun refresh() {}
        override suspend fun loadMore() { loadedMore++ }
        override suspend fun markSeen() { seen++ }
    }

    private class FakeSession : CallSession {
        override val state = MutableStateFlow(CallState())
        override val transitions get() = state
        override val videos: StateFlow<CallVideos> = MutableStateFlow(CallVideos())
        override fun startCall(peer: String, video: Boolean) {}
        override fun accept() {}
        override fun decline() {}
        override fun hangup() {}
        override fun setMuted(muted: Boolean) {}
        override fun setVideo(on: Boolean) {}
        override fun switchCamera() {}
        override fun setAudioRoute(route: AudioRoute) {}
    }

    private fun entry(at: String, id: Uuid = Uuid.random(), video: Boolean = false) = CallHistoryEntry(
        messageId = id, fileId = Uuid.random(), conversationId = Uuid.random(), peer = alice,
        direction = CallLogDirection.Incoming, outcome = CallOutcome.Completed, missed = false,
        video = video, durationSeconds = 5, at = Instant.parse(at),
    )

    private val contact = ContactUiModel(
        id = Uuid.random(), odinId = alice, name = "Alice", avatarInitials = "A",
        connectionState = ContactConnectionState.Connected,
    )

    private class Rig(val vm: CallsViewModel, val history: FakeHistory, val session: FakeSession, val deleted: MutableList<Pair<Uuid, Boolean>>)

    private fun rig(
        entries: List<CallHistoryEntry>,
        now: String = "2026-10-13T20:00:00Z",
        available: Boolean = true,
    ): Rig {
        val history = FakeHistory(entries)
        val session = FakeSession()
        val deleted = mutableListOf<Pair<Uuid, Boolean>>()
        val vm = CallsViewModel(
            history = history,
            contacts = flowOf(listOf(contact)),
            conversations = SeedableConversationLookup("owner.test"),
            session = session,
            callsAvailable = available,
            deleter = CallLogDeleter { id, everyone -> deleted += id to everyone },
            clock = { Instant.parse(now) },
            timeZone = { ist },
        )
        return Rig(vm, history, session, deleted)
    }

    @Test
    fun groupsByLocalDayAcrossMidnight() = runTest {
        // now = Oct 14 01:30 IST; 18:00Z is Oct 13 23:30 IST, 18:45Z is Oct 14 00:15 IST.
        val r = rig(listOf(
            entry("2026-10-13T18:45:00Z"),
            entry("2026-10-13T18:00:00Z"),
            entry("2026-10-13T10:00:00Z"),
            entry("2026-10-09T10:00:00Z"),
        ))
        val sections = r.vm.uiState.value.sections
        assertEquals(
            listOf(CallDay.Today, CallDay.Yesterday, CallDay.On(LocalDate(2026, 10, 9))),
            sections.map { it.day },
        )
        assertEquals(listOf(1, 2, 1), sections.map { it.rows.size })
        assertEquals("Alice", sections[0].rows[0].displayName)
        assertFalse(r.vm.uiState.value.isEmpty)
        r.vm.viewModelScope.cancel()
    }

    @Test
    fun emptyHistoryIsEmpty() = runTest {
        val r = rig(emptyList())
        assertTrue(r.vm.uiState.value.isEmpty)
        r.vm.viewModelScope.cancel()
    }

    @Test
    fun deleteIsForMeOnly() = runTest {
        val e = entry("2026-10-13T18:45:00Z")
        val r = rig(listOf(e))
        r.vm.onAction(CallsAction.Delete(e.messageId))
        assertEquals(listOf(e.messageId to false), r.deleted)
        r.vm.viewModelScope.cancel()
    }

    @Test
    fun openChatEmitsConversationWithoutMessageTarget() = runTest {
        val e = entry("2026-10-13T18:45:00Z")
        val r = rig(listOf(e))
        val events = mutableListOf<CallsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.vm.events.collect { events += it } }
        r.vm.onAction(CallsAction.OpenChat(e.conversationId))
        assertEquals(listOf<CallsEvent>(CallsEvent.OpenConversation(e.conversationId, null)), events)
        r.vm.viewModelScope.cancel()
    }

    @Test
    fun deleteFailureSurfacesAsEventAndKeepsTheRow() = runTest {
        val e = entry("2026-10-13T18:45:00Z")
        val history = FakeHistory(listOf(e))
        val vm = CallsViewModel(
            history = history, contacts = flowOf(listOf(contact)), conversations = SeedableConversationLookup("owner.test"),
            session = FakeSession(), callsAvailable = true,
            deleter = CallLogDeleter { _, _ -> error("boom") },
            clock = { Instant.parse("2026-10-13T20:00:00Z") }, timeZone = { ist },
        )
        val events = mutableListOf<CallsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.onAction(CallsAction.Delete(e.messageId))
        assertEquals(listOf<CallsEvent>(CallsEvent.DeleteFailed("boom")), events)
        assertEquals(1, vm.uiState.value.sections.sumOf { it.rows.size })
        vm.viewModelScope.cancel()
    }

    @Test
    fun callBackEmitsStartCallWithPeerAndVideoFlag() = runTest {
        val e = entry("2026-10-13T18:45:00Z")
        val r = rig(listOf(e))
        val events = mutableListOf<CallsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.vm.events.collect { events += it } }

        r.vm.onAction(CallsAction.CallBack(e, video = true))
        r.vm.onAction(CallsAction.OpenChat(e.conversationId, e.messageId))

        assertEquals(CallsEvent.StartCall(alice, true), events[0])
        assertEquals(CallsEvent.OpenConversation(e.conversationId, e.messageId), events[1])
        r.vm.viewModelScope.cancel()
    }

    @Test
    fun cannotCallWhileACallIsActiveOrUnavailable() = runTest {
        val e = entry("2026-10-13T18:45:00Z")
        val r = rig(listOf(e))
        val events = mutableListOf<CallsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.vm.events.collect { events += it } }
        assertTrue(r.vm.uiState.value.sections.single().rows.single().canCall)

        r.session.state.value = CallState(phase = CallPhase.Connected)
        assertFalse(r.vm.uiState.value.sections.single().rows.single().canCall)
        r.vm.onAction(CallsAction.CallBack(e, video = false))
        assertTrue(events.isEmpty())
        r.vm.viewModelScope.cancel()

        val unavailable = rig(listOf(e), available = false)
        assertFalse(unavailable.vm.uiState.value.sections.single().rows.single().canCall)
        unavailable.vm.viewModelScope.cancel()
    }

    @Test
    fun tabOpenedMarksSeenAndLoadMorePages() = runTest {
        val r = rig(emptyList())
        r.vm.onAction(CallsAction.TabOpened)
        r.vm.onAction(CallsAction.LoadMore)
        assertEquals(1, r.history.seen)
        assertEquals(1, r.history.loadedMore)
        r.vm.viewModelScope.cancel()
    }
}
