package id.homebase.core.ui.navigation

import id.homebase.core.notifications.NotificationNavigationEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for the silent notification-tap drop captured in
 * homebase_1785203524243.log (build 1788, 2026-07-28).
 *
 * Log evidence — three consecutive navigation events were accepted by an
 * AppViewModel and never reached AppNavHost:
 *
 *   01:09:42 (AppViewModel) navigationEvent forwarded: OpenConversation(… ShareIntent)
 *   01:10:19 (AppViewModel) navigationEvent forwarded: OpenConversation(… ShareIntent)
 *   01:45:51 (NotificationService) navigationEvent emit: OpenMoment(ab793c7f-…)
 *   01:45:51 (AppViewModel) navigationEvent forwarded: OpenMoment(ab793c7f-…)
 *   <silence — no "(AppNavHost) OpenMoment received">
 *
 * Every earlier event in the same process (13:26 through 23:39) went
 * emit -> forwarded -> AppNavHost cleanly. What changed in between: four
 * Activity onCreates (00:16:48 x2, 01:02:29, 01:02:36) with no process
 * restart.
 *
 * Cause: App.kt resolved AppViewModel with koinInject() against a
 * viewModelOf() (factory) definition, so each Activity recreation minted a
 * new AppViewModel outside any ViewModelStore. onCleared() was therefore
 * never called, viewModelScope was never cancelled, and every orphan kept
 * collecting NotificationService.navigationEvents — which is
 * `Channel(BUFFERED).receiveAsFlow()`, a **single-consumer** flow. N live
 * collectors round-robin the stream; an orphan that wins a turn logs
 * "forwarded" and trySends into its own channel that no AppNavHost reads.
 * With 5 instances and only the newest wired to the UI, 4 of every 5 taps
 * vanished.
 *
 * This test pins the channel semantics that make the leak fatal rather than
 * merely wasteful: it is the single-consumer split, not a lost subscription,
 * that eats the events. The fix is upstream — App.kt uses koinViewModel() so
 * exactly one instance is ever live.
 */
class NavigationEventSingleConsumerTest {

    private fun event(id: String) =
        NotificationNavigationEvent.OpenConversation(conversationId = id)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun multiple_collectors_split_a_single_consumer_channel_instead_of_each_seeing_every_event() =
        runTest {
            val source = Channel<NotificationNavigationEvent>(Channel.BUFFERED)
            val flow = source.receiveAsFlow()

            // Four orphaned AppViewModels plus the one AppNavHost is wired to.
            val received = List(5) { mutableListOf<NotificationNavigationEvent>() }
            val jobs = received.map { sink ->
                launch { flow.collect { sink += it } }
            }
            runCurrent()

            repeat(5) { source.trySend(event("0000000$it-0000-0000-0000-000000000000")) }
            runCurrent()

            val total = received.sumOf { it.size }
            assertEquals(
                5,
                total,
                "Channel.receiveAsFlow() must hand each event to exactly one collector; " +
                    "if this ever becomes a broadcast the orphan-instance leak stops being " +
                    "fatal and this test's premise is void",
            )
            assertTrue(
                received.any { it.size < 5 },
                "At least one collector must have missed events — this is the mechanism " +
                    "that swallowed the OpenMoment tap at 01:45:51",
            )

            jobs.forEach { it.cancel() }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun single_collector_receives_every_event() = runTest {
        // The post-fix shape: exactly one live AppViewModel, so every event
        // forwarded from NotificationService reaches AppNavHost.
        val source = Channel<NotificationNavigationEvent>(Channel.BUFFERED)
        val received = mutableListOf<NotificationNavigationEvent>()
        val job = launch { source.receiveAsFlow().collect { received += it } }
        runCurrent()

        val sent = List(5) { event("0000000$it-0000-0000-0000-000000000000") }
        sent.forEach { source.trySend(it) }
        runCurrent()

        assertEquals(sent, received.toList())
        job.cancel()
    }
}
