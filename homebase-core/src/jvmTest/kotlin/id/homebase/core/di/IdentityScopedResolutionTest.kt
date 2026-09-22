package id.homebase.core.di

import id.homebase.core.moments.services.MomentCreateFlowState
import id.homebase.core.session.IdentitySessionScope
import org.koin.core.Koin
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * The registration contract behind the share-into-New-Moment crash (#1584): an identity-scoped
 * definition lives only in the identity scope, so a bare `by inject()` — which resolves from
 * `_root_` — throws NoDefinitionFoundException at the first read.
 *
 * This pins the graph, not the call site: it passed before #1584 and after it, because the
 * registration was never the bug. It exists so the next reader of AppModule's
 * `scope(IdentitySessionQualifier) { scoped { MomentCreateFlowState() } }` can see, rather than
 * assume, that root resolution is not a supported path. The rule that catches the mistake itself
 * is ArchitectureTest's `IdentityScoped types are never injected from the root scope`.
 */
class IdentityScopedResolutionTest {

    private lateinit var koin: Koin
    private lateinit var session: IdentitySessionScope

    @BeforeTest
    fun setUp() {
        // createEagerInstances = false for the same reason as MomentDraftSurvivesLogoutTest: the
        // real graph has createdAtStart singletons that cannot construct headless.
        koin = koinApplication(createEagerInstances = false) { modules(allModules) }.koin
        session = IdentitySessionScope(koin)
    }

    @AfterTest
    fun tearDown() = koin.close()

    @Test
    fun `an identity-scoped definition is not resolvable from the root scope`() {
        session.open("frodo.dotyou.cloud")

        assertFailsWith<NoDefinitionFoundException>(
            "root resolution must fail loudly — a silent fallback would hand every identity the same draft",
        ) {
            koin.get<MomentCreateFlowState>()
        }
    }

    @Test
    fun `an identity-scoped definition resolves from the identity scope`() {
        session.open("frodo.dotyou.cloud")

        val first = session.requireScope().get<MomentCreateFlowState>()
        assertNotNull(first)
        assertSame(
            first,
            session.requireScope().get<MomentCreateFlowState>(),
            "the draft must be one instance per identity, or the composer and the audience picker hold different ones",
        )
    }
}
