package id.homebase.chat.calling.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import id.homebase.chat.services.SeedableConversationLookup
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallSession
import id.homebase.chat.services.calling.CallState
import id.homebase.chat.services.calling.CallVideos
import id.homebase.chat.services.calling.history.CallHistoryEntry
import id.homebase.chat.services.calling.history.CallHistorySource
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CallsTabSeenTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeHistory : CallHistorySource {
        override val entries = MutableStateFlow<List<CallHistoryEntry>>(emptyList())
        override val hasMore = MutableStateFlow(false)
        override val isLoading = MutableStateFlow(false)
        override val missedCount = MutableStateFlow(2)
        var seen = 0
        override suspend fun refresh() {}
        override suspend fun loadMore() {}
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

    @Test
    fun missedCallsAreMarkedSeenOnlyWhileTheCallsTabIsOnScreen() = runDesktopComposeUiTest(411, 900) {
        val history = FakeHistory()
        val vm = CallsViewModel(
            history = history,
            contacts = flowOf(emptyList()),
            conversations = SeedableConversationLookup("owner.test"),
            session = FakeSession(),
            callsAvailable = true,
            deleter = CallLogDeleter { _, _ -> },
        )
        var onCalls by mutableStateOf(false)
        setContent {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                if (onCalls) {
                    CallsTab(
                        viewModel = vm,
                        snackbarHostState = remember { SnackbarHostState() },
                        onStartCall = { _, _ -> },
                        onOpenConversation = { _, _ -> },
                        onOpenContact = {},
                        onFindContact = {},
                    )
                } else {
                    Text("Contacts")
                }
            }
        }
        waitForIdle()
        assertEquals(0, history.seen)
        assertEquals(2, vm.missedCount.value)
        onCalls = true
        waitForIdle()
        assertEquals(1, history.seen)
    }
}
