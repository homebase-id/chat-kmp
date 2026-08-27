package id.homebase.core.session

import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the Koin behaviour the migration depends on, so an upgrade that changes it fails here
 * rather than in the app.
 *
 * The rule, verified below and NOT what it looks like from the outside: **a definition can
 * only reach identity-scoped dependencies if the definition itself lives in the scope.**
 * Resolving a root-registered definition *through* the scope is not enough — Koin's
 * `resolveFromLinkedScopes` rebinds the resolution context to the linked (root) scope via
 * `newContextForScope`, so the definition's own `get()` calls run against root and cannot see
 * anything scoped.
 *
 * That is why the migration moves ViewModel definitions into the scope alongside the services
 * they consume, rather than leaving them at root and resolving them through it.
 */
class ScopeResolutionMechanicsTest {

    private class Service : IdentityScoped
    private class ScopedConsumer(val service: Service)
    private class RootConsumer(val service: Service)

    private val modules = module {
        // The same Consumer registered both ways, so the two can be compared directly.
        factory { RootConsumer(get()) }
        scope(IdentitySessionQualifier) {
            scoped { Service() }
            scoped { ScopedConsumer(get()) }
        }
    }

    @Test
    fun `a definition inside the scope reaches scoped dependencies`() {
        val koin = koinApplication(createEagerInstances = false) { modules(modules) }.koin
        val session = IdentitySessionScope(koin)
        session.open("frodo.dotyou.cloud")

        val consumer: ScopedConsumer = session.requireScope().get()

        assertSame(session.get<Service>(), consumer.service)
        koin.close()
    }

    @Test
    fun `a ROOT definition cannot reach scoped dependencies even when resolved through the scope`() {
        // The trap this test exists to document. It looks like it should work — you are asking
        // the identity scope for the object — but Koin rebinds the context to root on fallback,
        // so construction happens as if the scope were not there at all. Leaving a ViewModel at
        // root while moving its services into the scope fails exactly here, at runtime.
        val koin = koinApplication(createEagerInstances = false) { modules(modules) }.koin
        val session = IdentitySessionScope(koin)
        session.open("frodo.dotyou.cloud")

        assertFailsWith<Exception> { session.requireScope().get<RootConsumer>() }
        koin.close()
    }

    @Test
    fun `the same definition resolved from root cannot see the scoped dependency`() {
        // This is the failure mode the guard test exists to prevent: anything resolved outside
        // the identity scope simply cannot reach identity-scoped state.
        val koin = koinApplication(createEagerInstances = false) { modules(modules) }.koin
        IdentitySessionScope(koin).open("frodo.dotyou.cloud")

        val error = assertFailsWith<Exception> { koin.get<RootConsumer>() }
        assertTrue(
            error is NoDefinitionFoundException || error.cause is NoDefinitionFoundException,
            "expected a missing-definition failure, got $error",
        )
        koin.close()
    }

    @Test
    fun `each session gets its own instance`() {
        val koin = koinApplication(createEagerInstances = false) { modules(modules) }.koin
        val session = IdentitySessionScope(koin)

        session.open("frodo.dotyou.cloud")
        val frodos: ScopedConsumer = session.requireScope().get()
        session.close()

        session.open("sam.dotyou.cloud")
        val sams: ScopedConsumer = session.requireScope().get()

        assertNotSame(frodos.service, sams.service)
        koin.close()
    }
}
