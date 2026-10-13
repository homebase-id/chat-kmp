@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)

package id.homebase.chat.calling.history

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.viewModelScope
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallSession
import id.homebase.chat.services.calling.CallState
import id.homebase.chat.services.calling.CallVideos
import id.homebase.chat.services.convo.contact.ContactConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class NewCallViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeSession : CallSession {
        override val state = MutableStateFlow(CallState())
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

    private fun contact(name: String, state: ContactConnectionState) = ContactUiModel(
        id = Uuid.random(), odinId = OdinId("${name.lowercase()}.test"), name = name,
        avatarInitials = name.take(1), connectionState = state,
    )

    private val alice = contact("Alice", ContactConnectionState.Connected)
    private val bob = contact("Bob", ContactConnectionState.NotConnected)
    private val me = contact("Owner", ContactConnectionState.Connected)

    private fun vm(available: Boolean = true) = NewCallViewModel(
        contacts = flowOf(listOf(alice, bob, me)),
        self = flowOf(OwnerSession(me.odinId, "Owner", "Owner", null, null, null, null, null)),
        session = FakeSession(),
        callsAvailable = available,
    )

    @Test
    fun listsContactsOnlyWithoutSelfAndSearchFilters() = runTest {
        val vm = vm()
        assertEquals(listOf("Alice", "Bob"), vm.uiState.value.groups.flatMap { g -> g.rows.map { it.contact.name } })
        assertEquals(listOf(true, false), vm.uiState.value.groups.flatMap { g -> g.rows.map { it.connected } })
        vm.searchTextState.setTextAndPlaceCursorAtEnd("ali")
        Snapshot.sendApplyNotifications()
        assertEquals(listOf("Alice"), vm.uiState.value.groups.flatMap { g -> g.rows.map { it.contact.name } })
        vm.searchTextState.setTextAndPlaceCursorAtEnd("owner")
        Snapshot.sendApplyNotifications()
        assertEquals(emptyList(), vm.uiState.value.groups)
        vm.viewModelScope.cancel()
    }

    @Test
    fun connectedContactStartsAndUnconnectedCannot() = runTest {
        val vm = vm()
        val events = mutableListOf<NewCallEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.onAction(NewCallAction.Start(bob, video = true))
        assertEquals(emptyList(), events)
        vm.onAction(NewCallAction.Start(alice, video = true))
        assertEquals(listOf<NewCallEvent>(NewCallEvent.StartCall(alice.odinId, true)), events)
        vm.viewModelScope.cancel()
    }

    @Test
    fun unavailableCallsCannotStart() = runTest {
        val vm = vm(available = false)
        val events = mutableListOf<NewCallEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.onAction(NewCallAction.Start(alice, video = false))
        assertNull(events.firstOrNull())
        vm.viewModelScope.cancel()
    }
}
