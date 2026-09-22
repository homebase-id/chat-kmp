package id.homebase.core.session

import org.koin.core.Koin
import org.koin.core.scope.Scope
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The orderings #1491 froze on, made deterministic.
 *
 * The failing iOS launch resolved auth and opened the Koin identity scope *before* the ComposeView
 * existed, so first composition navigated straight to the main screen and resolved its whole
 * battery of identity-scoped ViewModels in one cold burst while the session was still settling.
 * These tests reproduce that shape — a transition in flight against a burst of resolution — and
 * pin the two invariants that shape depends on: identity teardown never runs under the session
 * lock, and a racing re-open never swaps the scope out from under the resolvers.
 *
 * JVM-only: it needs real threads and latches. Note this does NOT reproduce the production freeze,
 * whose cause is still unconfirmed — it guards the hardening, not a diagnosis.
 */
class IdentitySessionConcurrencyTest {

    private class Service : IdentityScoped {
        val closed = AtomicBoolean(false)
        /** Lets a test hold teardown open and observe what the rest of the app can still do. */
        @Volatile var onCloseHook: (() -> Unit)? = null
    }

    private lateinit var koin: Koin
    private lateinit var session: IdentitySessionScope

    @BeforeTest
    fun setUp() {
        koin = koinApplication(createEagerInstances = false) {
            modules(
                module {
                    scope(IdentitySessionQualifier) {
                        scoped { Service() } onClose { service ->
                            service?.closed?.set(true)
                            service?.onCloseHook?.invoke()
                        }
                    }
                },
            )
        }.koin
        session = IdentitySessionScope(koin)
    }

    @AfterTest
    fun tearDown() = koin.close()

    @Test
    fun `identity teardown runs without holding the session lock`() {
        // Closing a Koin scope drops every instance in it and fires each onClose — arbitrary app
        // teardown (coroutine scopes cancelled, collectors torn down, ViewModels cleared). While
        // that ran under the session lock, any concurrent open()/close() queued behind whatever
        // the slowest onClose decided to do.
        session.open(FRODO)
        val service = session.get<Service>()

        val teardownStarted = CountDownLatch(1)
        val releaseTeardown = CountDownLatch(1)
        service.onCloseHook = {
            teardownStarted.countDown()
            releaseTeardown.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        val closer = thread { session.close() }
        assertTrue(teardownStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "teardown never ran")

        val opener = thread { session.open(SAM) }
        opener.join(2_000)
        val blocked = opener.isAlive

        releaseTeardown.countDown()
        closer.join()
        opener.join()

        assertFalse(blocked, "open() blocked behind identity teardown — the lock is held across scope.close()")
    }

    @Test
    fun `a re-open racing a first-composition burst never swaps the live scope`() {
        // The failing ordering: the scope is already open when composition starts, and the
        // authenticated transition runs a second time (headless bootstrap -> promoteToForeground)
        // while the screen resolves out of it. The re-open must stay a no-op even when it races.
        session.open(FRODO)
        val service = session.get<Service>()

        val start = CyclicBarrier(REOPENERS + RESOLVERS)
        val failure = AtomicReference<Throwable?>(null)

        val reopeners = List(REOPENERS) {
            thread {
                runCatching { start.await(); repeat(BURST) { session.open(FRODO) } }
                    .onFailure(failure::set)
            }
        }
        val resolvers = List(RESOLVERS) {
            thread {
                runCatching {
                    start.await()
                    repeat(BURST) { assertSame(service, session.get<Service>()) }
                }.onFailure(failure::set)
            }
        }

        (reopeners + resolvers).forEach { it.join(TIMEOUT_SECONDS * 1_000) }
        (reopeners + resolvers).forEach { assertFalse(it.isAlive, "a thread deadlocked") }
        failure.get()?.let { throw it }

        assertFalse(service.closed.get(), "the live identity's state was torn down by a re-open")
        assertSame(service, session.get<Service>())
        assertEquals(FRODO, session.identity)
    }

    @Test
    fun `opens racing from cold agree on one scope and leave no other behind`() {
        // Every racer passes the fast path (nothing is open yet) and builds its own candidate,
        // because scope ids are now unique per open — that is what stops a racing pair colliding
        // on Koin's contains-then-put. Exactly one candidate may survive: the rest have to be
        // handed back as the winner's scope AND destroyed, or the registry grows a dead scope
        // per login.
        val returned = ConcurrentLinkedQueue<Scope>()
        val start = CyclicBarrier(REOPENERS)
        val threads = List(REOPENERS) {
            thread { start.await(); returned += session.open(FRODO) }
        }
        threads.forEach { it.join(TIMEOUT_SECONDS * 1_000) }
        threads.forEach { assertFalse(it.isAlive, "a thread deadlocked") }

        val live = session.requireScope()
        assertEquals(REOPENERS, returned.size)
        assertEquals(setOf(live), returned.toSet(), "racing opens handed out more than one scope")
        assertFalse(live.closed)

        val prefix = live.id.substringBeforeLast('#')
        val stillRegistered = (1..REOPENERS * 2).count { koin.getScopeOrNull("$prefix#$it") != null }
        assertEquals(1, stillRegistered, "a discarded scope was left in Koin's registry")
    }

    @Test
    fun `a close racing an open leaves no half-open session`() {
        // Which of the two wins is decided by the order their state transitions take the lock —
        // the same rule as before. What must hold either way: the scope Koin still knows about is
        // the one the session points at, and nothing is left created-but-unreachable.
        repeat(30) {
            session.open(FRODO)
            val start = CyclicBarrier(2)
            val opener = thread { start.await(); session.open(SAM) }
            val closer = thread { start.await(); session.close() }
            opener.join(TIMEOUT_SECONDS * 1_000)
            closer.join(TIMEOUT_SECONDS * 1_000)
            assertFalse(opener.isAlive || closer.isAlive, "a thread deadlocked")

            val live = session.scopeOrNull
            if (live == null) {
                assertEquals(null, session.identity)
            } else {
                assertFalse(live.closed)
                assertSame(live, koin.getScopeOrNull(live.id))
            }
            session.close()
        }
    }

    private companion object {
        const val FRODO = "frodo.dotyou.cloud"
        const val SAM = "sam.dotyou.cloud"
        const val REOPENERS = 4
        const val RESOLVERS = 4
        const val BURST = 200
        const val TIMEOUT_SECONDS = 10L
    }
}
