package id.homebase.core.session

import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.onClose
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IdentitySessionScopeTest {

    /** Stands in for any per-identity service; [closed] records that teardown actually ran. */
    private class Session : IdentityScoped {
        var closed = false
    }

    private lateinit var koin: Koin
    private lateinit var session: IdentitySessionScope

    @BeforeTest
    fun setUp() {
        koin = koinApplication {
            modules(
                module {
                    scope(IdentitySessionQualifier) {
                        scoped { Session() } onClose { it?.closed = true }
                    }
                },
            )
        }.koin
        session = IdentitySessionScope(koin)
    }

    @AfterTest
    fun tearDown() = koin.close()

    @Test
    fun `starts closed`() {
        assertFalse(session.isOpen)
        assertNull(session.identity)
        assertNull(session.scopeOrNull)
    }

    @Test
    fun `resolving while logged out throws rather than handing back a stale object`() {
        assertFailsWith<IllegalStateException> { session.requireScope() }
        assertNull(session.getOrNull<Session>())
    }

    @Test
    fun `opening twice for the same identity keeps the live scope`() {
        // The authenticated transition runs more than once per session — a headless bootstrap
        // that later promotes to foreground. The second pass must not tear down live services.
        val first = session.open("frodo.dotyou.cloud")
        val service = session.get<Session>()

        val second = session.open("frodo.dotyou.cloud")

        assertSame(first, second)
        assertSame(service, session.get<Session>())
        assertFalse(service.closed)
    }

    @Test
    fun `switching identity destroys the previous identity's state`() {
        session.open("frodo.dotyou.cloud")
        val frodosService = session.get<Session>()

        session.open("sam.dotyou.cloud")
        val samsService = session.get<Session>()

        assertTrue(frodosService.closed, "Frodo's service should have been torn down")
        assertNotSame(frodosService, samsService)
        assertFalse(samsService.closed)
        assertEquals("sam.dotyou.cloud", session.identity)
    }

    @Test
    fun `close destroys scoped state and logging back in builds it fresh`() {
        session.open("frodo.dotyou.cloud")
        val before = session.get<Session>()

        session.close()

        assertTrue(before.closed)
        assertFalse(session.isOpen)
        assertNull(session.identity)

        // Same identity again: a fresh instance, not the one we just tore down. This is the
        // whole point of the mechanism — logout is a destruction, not a reset.
        session.open("frodo.dotyou.cloud")
        assertNotSame(before, session.get<Session>())
    }

    @Test
    fun `close is idempotent`() {
        // Logout can arrive by more than one path (sign-out, token expiry, an identity switch)
        // and the second must not throw.
        session.open("frodo.dotyou.cloud")
        session.close()
        session.close()
        assertFalse(session.isOpen)
    }
}
