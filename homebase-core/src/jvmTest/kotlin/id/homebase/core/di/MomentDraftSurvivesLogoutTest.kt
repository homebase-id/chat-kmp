package id.homebase.core.di

import id.homebase.core.moments.services.MomentCreateFlowState
import id.homebase.core.session.IdentitySessionScope
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * A moment draft — the user's photos and description — must not survive a logout into the
 * next identity's session.
 *
 * [MomentCreateFlowState] is registered app-lifetime (`single { }` in AppModule) and its
 * `clear()` is called from exactly one place: MomentAudienceViewModel, on a *successful
 * post*. Nothing clears it on logout — it is not in `onPostAuthenticated`'s reset list and
 * has no SessionEnded listener. So abandoning a compose mid-flow (Continue → audience
 * picker, then log out without posting) leaves the draft in memory, and
 * MomentComposeViewModel seeds its UI state from `restoreFromDraft()` on construction with
 * no identity check.
 *
 * This test resolves from the real DI graph rather than a hand-built one, so it describes
 * what the app actually does. It is expected to FAIL until MomentCreateFlowState moves into
 * the identity scope — that failure is the point: it is the baseline proving the leak is
 * real, not merely argued.
 */
class MomentDraftSurvivesLogoutTest {

    private lateinit var koin: Koin
    private lateinit var session: IdentitySessionScope

    @BeforeTest
    fun setUp() {
        // createEagerInstances = false: the real graph has createdAtStart singletons (the
        // desktop notification bridge among them) that cannot construct headless. We only
        // need the definitions, and Koin resolves the rest lazily.
        koin = koinApplication(createEagerInstances = false) { modules(allModules) }.koin
        session = IdentitySessionScope(koin)
    }

    @AfterTest
    fun tearDown() = koin.close()

    @Test
    fun `a moment draft does not survive into the next identity's session`() {
        session.open("frodo.dotyou.cloud")
        resolveFlowState().setDraft(
            MomentCreateFlowState.Draft(attachments = emptyList(), description = "Frodo's holiday snaps"),
        )

        // Logout, then a different identity logs in.
        session.close()
        session.open("sam.dotyou.cloud")

        assertNull(
            resolveFlowState().draft.value,
            "Sam's composer would open on Frodo's draft — his photos and description",
        )
    }

    /**
     * Resolve the way the app does: out of the identity scope, falling back to the root
     * scope while the definition is still registered app-lifetime. That fallback is what
     * makes this test meaningful before the migration and honest after it.
     */
    private fun resolveFlowState(): MomentCreateFlowState = session.requireScope().get()
}
