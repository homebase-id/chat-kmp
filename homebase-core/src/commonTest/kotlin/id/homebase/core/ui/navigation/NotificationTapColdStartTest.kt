package id.homebase.core.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import id.homebase.core.notifications.NotificationNavigationEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.uuid.Uuid

/**
 * Regression test for the cold-start crash captured in homebase.log lines 8856-8887:
 *
 *   IllegalArgumentException: No destination with route o16 is on the
 *   NavController's back stack. The current destination is nv0 route=app-loading
 *
 * Sequence from the log:
 *   1. FCM push delivered into a killed process; NotificationService buffers
 *      OpenConversation into its Channel(BUFFERED) (PR #314 fix).
 *   2. App starts; NavController lands on AppLoading.
 *   3. AuthState transitions to Authenticated.
 *   4. AppNavHost's navigationEvents collector drains the buffered event and
 *      calls selectConversationOnChatList -> getBackStackEntry<Route.ChatList>()
 *      while NavController is still on AppLoading -> IAE -> process crash.
 *
 * The collector must wait for Route.ChatList to reach the back stack before
 * dispatching. This test drives a minimal NavHost(AppLoading, ChatList) and
 * fires the event during the AppLoading window; with the fix the event is
 * held until ChatList is pushed, then applied.
 */
@Serializable
internal data object TestAppLoading

@Serializable
internal data object TestChatList

@Serializable
internal data object TestDetail

@OptIn(ExperimentalTestApi::class)
class NotificationTapColdStartTest {

    @Test
    fun cold_start_notification_tap_waits_for_chatlist_on_backstack() = runComposeUiTest {
        val events = Channel<NotificationNavigationEvent>(Channel.BUFFERED)
        val authReady = mutableStateOf(false)

        setContent {
            val navController = rememberNavController()

            LaunchedEffect(Unit) {
                events.consumeAsFlow().collect { event ->
                    if (event is NotificationNavigationEvent.OpenConversation) {
                        val id = Uuid.parseOrNull(event.conversationId) ?: return@collect
                        navController.currentBackStackEntryFlow
                            .first { it.destination.hasRoute(TestChatList::class) }
                        navController.getBackStackEntry<TestChatList>()
                            .savedStateHandle["pendingConversationId"] = id.toString()
                    }
                }
            }

            NavHost(navController, startDestination = TestAppLoading) {
                composable<TestAppLoading> {
                    LaunchedEffect(authReady.value) {
                        if (authReady.value) {
                            navController.navigate(TestChatList) {
                                popUpTo(TestAppLoading) { inclusive = true }
                            }
                        }
                    }
                    Text("loading")
                }
                composable<TestChatList> { backStackEntry ->
                    val pending by backStackEntry.savedStateHandle
                        .getStateFlow<String?>("pendingConversationId", null)
                        .collectAsState()
                    Text("chatlist pending=${pending ?: "null"}")
                }
            }
        }

        waitForIdle()
        onNodeWithText("loading").assertExists()

        events.trySend(
            NotificationNavigationEvent.OpenConversation(
                "cc0577d2-2c92-4843-b08d-166e05ad4c19"
            )
        )
        waitForIdle()
        onNodeWithText("loading").assertExists()

        authReady.value = true
        waitForIdle()

        onNodeWithText("chatlist pending=cc0577d2-2c92-4843-b08d-166e05ad4c19")
            .assertExists()
    }

    @Test
    fun unguarded_collector_reproduces_production_crash_on_cold_start() = runComposeUiTest {
        // Negative control: same scenario, but the collector touches
        // getBackStackEntry<ChatList>() immediately without awaiting the
        // AppLoading -> ChatList transition. This mirrors the pre-fix call site
        // in AppNavHost.kt line 162-175 / selectConversationOnChatList line
        // 577-580 and must surface an IllegalArgumentException (proving the
        // positive test above actually exercises the race).
        val events = Channel<NotificationNavigationEvent>(Channel.BUFFERED)
        val caught = mutableStateOf<Throwable?>(null)

        setContent {
            val navController = rememberNavController()

            LaunchedEffect(Unit) {
                events.consumeAsFlow().collect { event ->
                    if (event is NotificationNavigationEvent.OpenConversation) {
                        val id = Uuid.parseOrNull(event.conversationId) ?: return@collect
                        try {
                            navController.getBackStackEntry<TestChatList>()
                                .savedStateHandle["pendingConversationId"] = id.toString()
                        } catch (t: IllegalArgumentException) {
                            caught.value = t
                        }
                    }
                }
            }

            NavHost(navController, startDestination = TestAppLoading) {
                composable<TestAppLoading> { Text("loading") }
                composable<TestChatList> { Text("chatlist") }
            }
        }

        waitForIdle()
        events.trySend(
            NotificationNavigationEvent.OpenConversation(
                "cc0577d2-2c92-4843-b08d-166e05ad4c19"
            )
        )
        waitForIdle()

        val thrown = caught.value
        kotlin.test.assertNotNull(
            thrown,
            "Unguarded getBackStackEntry<ChatList>() must throw while NavController is on AppLoading — this is the production crash we're guarding against"
        )
        kotlin.test.assertTrue(
            thrown.message?.contains("back stack") == true,
            "Expected back-stack IAE, got: ${thrown.message}"
        )
    }

    /**
     * Regression test for the warm-path drop captured in homebase.log lines 222+:
     *
     *   (MainActivity) Notification intent detected (conversation: b185a5ed-…)
     *   — then silence; no navigation occurs.
     *
     * Scenario: app is warm, user is currently on a conversation Detail screen.
     * A notification tap for a *different* conversation arrives.
     *
     * PR #322 gated the collector on
     *   currentBackStackEntryFlow.first { it.destination.hasRoute(ChatList::class) }
     * which checks the *top* back-stack entry. When the user is on Detail, that
     * predicate never matches (top is Detail; ChatList sits underneath), so
     * `first {}` suspends forever and the notification is silently dropped.
     *
     * The correct gate is membership in the back stack, not top-of-stack:
     *   currentBackStack.first { stack -> stack.any { it.destination.hasRoute(ChatList::class) } }
     * which returns immediately when ChatList is anywhere in the stack. The
     * subsequent popBackStack(ChatList, inclusive=false) then brings ChatList
     * back to the top, which reads pendingConversationId and routes.
     */
    @Test
    fun warm_start_notification_tap_from_detail_navigates_via_chatlist() = runComposeUiTest {
        val events = Channel<NotificationNavigationEvent>(Channel.BUFFERED)
        val authReady = mutableStateOf(false)
        val navigateToDetail = Channel<Unit>(Channel.BUFFERED)
        var navControllerRef: androidx.navigation.NavHostController? = null

        setContent {
            val navController = rememberNavController()
            navControllerRef = navController

            LaunchedEffect(Unit) {
                events.consumeAsFlow().collect { event ->
                    if (event is NotificationNavigationEvent.OpenConversation) {
                        val id = Uuid.parseOrNull(event.conversationId) ?: return@collect
                        // The fix under test: wait for ChatList to be *anywhere*
                        // in the stack, not for it to be the top entry.
                        navController.currentBackStack
                            .first { stack -> stack.any { it.destination.hasRoute(TestChatList::class) } }
                        navController.getBackStackEntry<TestChatList>()
                            .savedStateHandle["pendingConversationId"] = id.toString()
                        navController.popBackStack(TestChatList, inclusive = false)
                    }
                }
            }

            LaunchedEffect(Unit) {
                navigateToDetail.consumeAsFlow().collect {
                    navController.navigate(TestDetail)
                }
            }

            NavHost(navController, startDestination = TestAppLoading) {
                composable<TestAppLoading> {
                    LaunchedEffect(authReady.value) {
                        if (authReady.value) {
                            navController.navigate(TestChatList) {
                                popUpTo(TestAppLoading) { inclusive = true }
                            }
                        }
                    }
                    Text("loading")
                }
                composable<TestChatList> { backStackEntry ->
                    val pending by backStackEntry.savedStateHandle
                        .getStateFlow<String?>("pendingConversationId", null)
                        .collectAsState()
                    Text("chatlist pending=${pending ?: "null"}")
                }
                composable<TestDetail> {
                    Text("detail")
                }
            }
        }

        // Warm app up through to Detail.
        authReady.value = true
        waitForIdle()
        navigateToDetail.trySend(Unit)
        waitForIdle()
        onNodeWithText("detail").assertExists()

        // Notification tap arrives while on Detail. With the top-only gate this
        // suspends forever; with the membership gate it proceeds immediately.
        events.trySend(
            NotificationNavigationEvent.OpenConversation(
                "cc0577d2-2c92-4843-b08d-166e05ad4c19"
            )
        )
        waitForIdle()

        onNodeWithText("chatlist pending=cc0577d2-2c92-4843-b08d-166e05ad4c19")
            .assertExists()
        kotlin.test.assertNotNull(navControllerRef)
    }

    @Test
    fun broken_top_only_gate_hangs_when_on_detail() = runComposeUiTest {
        // Negative control: same warm-on-Detail scenario as the test above,
        // but with the pre-fix top-only gate. Asserts that pendingConversationId
        // is NOT applied to ChatList's savedStateHandle (the collector is
        // suspended on `first{}` that can never match the top entry). If this
        // test ever starts applying the pending id, the positive test above
        // isn't actually exercising the regression.
        val events = Channel<NotificationNavigationEvent>(Channel.BUFFERED)
        val authReady = mutableStateOf(false)
        val navigateToDetail = Channel<Unit>(Channel.BUFFERED)
        var navControllerRef: androidx.navigation.NavHostController? = null

        setContent {
            val navController = rememberNavController()
            navControllerRef = navController

            LaunchedEffect(Unit) {
                events.consumeAsFlow().collect { event ->
                    if (event is NotificationNavigationEvent.OpenConversation) {
                        val id = Uuid.parseOrNull(event.conversationId) ?: return@collect
                        // The BROKEN top-only gate from PR #322.
                        navController.currentBackStackEntryFlow
                            .first { it.destination.hasRoute(TestChatList::class) }
                        navController.getBackStackEntry<TestChatList>()
                            .savedStateHandle["pendingConversationId"] = id.toString()
                    }
                }
            }

            LaunchedEffect(Unit) {
                navigateToDetail.consumeAsFlow().collect {
                    navController.navigate(TestDetail)
                }
            }

            NavHost(navController, startDestination = TestAppLoading) {
                composable<TestAppLoading> {
                    LaunchedEffect(authReady.value) {
                        if (authReady.value) {
                            navController.navigate(TestChatList) {
                                popUpTo(TestAppLoading) { inclusive = true }
                            }
                        }
                    }
                    Text("loading")
                }
                composable<TestChatList> {
                    Text("chatlist")
                }
                composable<TestDetail> {
                    Text("detail")
                }
            }
        }

        authReady.value = true
        waitForIdle()
        navigateToDetail.trySend(Unit)
        waitForIdle()
        onNodeWithText("detail").assertExists()

        events.trySend(
            NotificationNavigationEvent.OpenConversation(
                "cc0577d2-2c92-4843-b08d-166e05ad4c19"
            )
        )
        waitForIdle()

        // With the broken gate the collector is suspended on `first {}` waiting
        // for the top entry to be ChatList — which never happens while the user
        // is on Detail. Peek directly into ChatList's savedStateHandle to prove
        // the pending id was never written.
        val controller = navControllerRef
            ?: kotlin.test.fail("NavController not captured")
        val chatListEntry = controller.getBackStackEntry<TestChatList>()
        val pending = chatListEntry.savedStateHandle.get<String?>("pendingConversationId")
        kotlin.test.assertNull(
            pending,
            "Broken top-only gate must leave pendingConversationId unset while user is on Detail — this is the warm-path regression we're guarding against"
        )
    }

    /**
     * Regression test for the iOS share-extension silent failure captured in
     * Gabriel's homebase.log (2026-04-27). Pattern in the log:
     *
     *   ShareHandlerBridge Incoming share for conversation: e028c85f-…
     *   AppViewModel Handling share intent for conversation: e028c85f-…
     *   AppNavHost OpenConversation received: id=e028c85f-…, currentDest=home
     *   AppNavHost ChatList present in stack (size=3), popping to it
     *   AppNavHost popBackStack(ChatList)=true
     *   <silence — no selectConversation, no loadMessagesForConversation>
     *
     * Cause: AppNavHost only popped the back stack and trusted PendingNotificationTap
     * to drive selection. Notification taps work that way because NotificationService
     * seeds the singleton with (conversationId, messageId). Share intents have **no**
     * messageId and never seed the singleton, so the user landed on ChatList and the
     * target conversation was never selected — the shared file was never delivered.
     *
     * Fix: tag share-originated OpenConversation with Source.ShareIntent and have
     * AppNavHost additionally call selectConversationOnChatList(id), which writes
     * pendingConversationId to ChatList's savedStateHandle. The ChatList composable's
     * LaunchedEffect picks that up and calls ConversationListViewModel.selectConversation,
     * which runs processPendingSharedContent so the file lands in the conversation.
     *
     * This test reproduces the dispatch logic from AppNavHost.kt and asserts that a
     * Source.ShareIntent event applies pendingConversationId, while a Source.NotificationTap
     * event does NOT (resolution stays on the PendingNotificationTap path).
     */
    @Test
    fun share_intent_writes_pending_conversation_id_notification_tap_does_not() = runComposeUiTest {
        val events = Channel<NotificationNavigationEvent>(Channel.BUFFERED)
        val authReady = mutableStateOf(false)
        val navigateToDetail = Channel<Unit>(Channel.BUFFERED)
        var navControllerRef: androidx.navigation.NavHostController? = null

        setContent {
            val navController = rememberNavController()
            navControllerRef = navController

            LaunchedEffect(Unit) {
                events.consumeAsFlow().collect { event ->
                    if (event is NotificationNavigationEvent.OpenConversation) {
                        val id = Uuid.parseOrNull(event.conversationId) ?: return@collect
                        navController.currentBackStack
                            .first { stack -> stack.any { it.destination.hasRoute(TestChatList::class) } }
                        navController.popBackStack(TestChatList, inclusive = false)
                        // Mirrors AppNavHost: only ShareIntent writes savedStateHandle.
                        // NotificationTap leaves it to PendingNotificationTap (not in this test).
                        if (event.source == NotificationNavigationEvent.OpenConversation.Source.ShareIntent) {
                            navController.getBackStackEntry<TestChatList>()
                                .savedStateHandle["pendingConversationId"] = id.toString()
                        }
                    }
                }
            }

            LaunchedEffect(Unit) {
                navigateToDetail.consumeAsFlow().collect {
                    navController.navigate(TestDetail)
                }
            }

            NavHost(navController, startDestination = TestAppLoading) {
                composable<TestAppLoading> {
                    LaunchedEffect(authReady.value) {
                        if (authReady.value) {
                            navController.navigate(TestChatList) {
                                popUpTo(TestAppLoading) { inclusive = true }
                            }
                        }
                    }
                    Text("loading")
                }
                composable<TestChatList> { backStackEntry ->
                    val pending by backStackEntry.savedStateHandle
                        .getStateFlow<String?>("pendingConversationId", null)
                        .collectAsState()
                    Text("chatlist pending=${pending ?: "null"}")
                }
                composable<TestDetail> {
                    Text("detail")
                }
            }
        }

        // Warm path: user is on Detail when shares arrive (matches Gabriel's log,
        // where currentDest=home for each share invocation).
        authReady.value = true
        waitForIdle()
        navigateToDetail.trySend(Unit)
        waitForIdle()
        onNodeWithText("detail").assertExists()

        // 1) Notification tap: must NOT write savedStateHandle (covered by
        //    PendingNotificationTap in production).
        events.trySend(
            NotificationNavigationEvent.OpenConversation(
                conversationId = "11111111-1111-1111-1111-111111111111",
                source = NotificationNavigationEvent.OpenConversation.Source.NotificationTap,
            )
        )
        waitForIdle()

        val controller = navControllerRef
            ?: kotlin.test.fail("NavController not captured")
        val chatListEntry = controller.getBackStackEntry<TestChatList>()
        kotlin.test.assertNull(
            chatListEntry.savedStateHandle.get<String?>("pendingConversationId"),
            "NotificationTap must not write pendingConversationId — that path goes through PendingNotificationTap"
        )

        // 2) Share intent: must write savedStateHandle so ChatList's observer
        //    routes through ConversationListViewModel.selectConversation, which
        //    in turn runs processPendingSharedContent.
        events.trySend(
            NotificationNavigationEvent.OpenConversation(
                conversationId = "e028c85f-f04d-4600-8946-3d3a8be543f4",
                source = NotificationNavigationEvent.OpenConversation.Source.ShareIntent,
            )
        )
        waitForIdle()

        onNodeWithText("chatlist pending=e028c85f-f04d-4600-8946-3d3a8be543f4")
            .assertExists()
    }
}
