package id.homebase.chat.services.convo

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reproduces the Android launch crash where the conversation overview renders
 * (only the cached "note to self" row + a spinner) and then the process dies —
 * with no table-flip recovery screen.
 *
 * ## The mechanism
 *
 * `ConversationListViewModel`'s init launches, on the bare `viewModelScope`
 * (SupervisorJob + Dispatchers.Main, **no** CoroutineExceptionHandler), and with
 * **no** local try/catch:
 *
 * ```
 * viewModelScope.launch {
 *     conversationStream.conversations.first { it.dataReady }
 *     conversationService.ensureNoteToSelfExists()
 * }
 * ```
 *
 * `dataReady` does **not** imply "credentials are ready". Per the debounce comment
 * a few lines above that launch, the first ready list is produced "within a few ms
 * of vmInit" from **cached conversations + a credentials-synthesized session** — so
 * `first { it.dataReady }` can unblock *before* `CredentialsManager.activeCredentials`
 * has been populated by the real auth/connect lifecycle. A freshly-wiped or
 * just-created DB (e.g. after the stale-schema self-heal) makes the cached list
 * resolve even faster, widening that window.
 *
 * `ensureNoteToSelfExists()`'s very first statement is
 * `credentialsManager.requireActiveDomain()`, which throws
 * `IllegalStateException("No active credentials set")` when credentials aren't set
 * yet. The method's `catch (e: Throwable)` only logs/audits and then **rethrows**,
 * so the exception leaves the suspend fun. Because the launch site has no try/catch
 * and `viewModelScope` carries no CoroutineExceptionHandler, the exception reaches
 * `Thread.setDefaultUncaughtExceptionHandler` → `GlobalCrashHandler` →
 * `Process.killProcess`. The app crashes exactly when the symptom describes: just
 * after the cached note-to-self row is on screen.
 *
 * These two tests pin both halves: the deterministic throw, and the fact that the
 * production launch pattern does not contain it.
 */
class NoteToSelfBootstrapCrashTest {

    /**
     * Root cause, deterministic: when the conversation list becomes `dataReady`
     * from cached data before credentials settle, `ensureNoteToSelfExists()` throws
     * `IllegalStateException` out of the suspend fun (it rethrows after auditing).
     */
    @Test
    fun ensureNoteToSelfExists_beforeCredentialsReady_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)

            // Model the race: the cached/synthesized list fired `dataReady` and the
            // bootstrap ran, but the real active credentials are not set yet.
            fixture.credentialsManager.removeActiveCredentials()

            val ex = assertFailsWith<IllegalStateException> {
                service.ensureNoteToSelfExists()
            }
            assertTrue(
                ex.message?.contains("No active credentials") == true,
                "expected the requireActiveDomain() guard to be the thrower, was: ${ex.message}",
            )
        }
    }

    /**
     * Propagation, modelling the production call site: launching the bootstrap on a
     * `SupervisorJob`-only scope (the shape of `viewModelScope`) with no try/catch
     * lets the exception escape the launch block uncaught.
     *
     * Here a CoroutineExceptionHandler stands in for "the uncaught path" so the test
     * can observe delivery without killing the test JVM. On the real `viewModelScope`
     * there is *no* such handler, so the same escaping exception lands on
     * `Thread.setDefaultUncaughtExceptionHandler` → `GlobalCrashHandler` and the
     * process is killed. The fix (wrap the launch body in try/catch, or give the
     * bootstrap a contained handler) would stop the exception here and this handler
     * would never fire.
     */
    @Test
    fun ensureNoteToSelfExists_launchedLikeTheViewModel_escapesUncaught() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            fixture.credentialsManager.removeActiveCredentials()

            var escaped: Throwable? = null
            val handlerStandingInForCrashHandler = CoroutineExceptionHandler { _, e ->
                escaped = e
            }
            // SupervisorJob + no try/catch in the body, exactly like viewModelScope. The
            // only difference is the observable handler we attach to catch the escape;
            // viewModelScope has none, so on-device the escape goes to the JVM default
            // uncaught handler instead. Unconfined runs the body inline so the throw
            // (requireActiveDomain, before any real suspension) propagates synchronously.
            val viewModelLikeScope =
                CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handlerStandingInForCrashHandler)

            // The exact unguarded pattern from ConversationListViewModel.init.
            val job = viewModelLikeScope.launch {
                service.ensureNoteToSelfExists()
            }
            job.join()

            assertNotNull(
                escaped,
                "the bootstrap exception was contained — expected it to escape the launch uncaught",
            )
            assertTrue(
                escaped is IllegalStateException,
                "the bootstrap exception escaped the launch uncaught (this is the crash); was: $escaped",
            )
        }
    }

    /**
     * The fix, modelling the patched call site in `ConversationListViewModel`: the
     * same unguarded scope, but the bootstrap body is wrapped in try/catch (and, in
     * production, additionally gated on `credentialsFlow.first { it != null }`). The
     * exception is contained and logged instead of escaping — the handler standing in
     * for the crash path never fires, so the app survives.
     */
    @Test
    fun ensureNoteToSelfExists_containedLikeTheFix_doesNotEscape() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            fixture.credentialsManager.removeActiveCredentials()

            var escaped: Throwable? = null
            val viewModelLikeScope = CoroutineScope(
                SupervisorJob() + Dispatchers.Unconfined +
                    CoroutineExceptionHandler { _, e -> escaped = e },
            )

            val job = viewModelLikeScope.launch {
                // The fix: contain the best-effort bootstrap (retried next launch).
                try {
                    service.ensureNoteToSelfExists()
                } catch (_: Throwable) {
                    // swallowed-and-logged in production (Logger.w)
                }
            }
            job.join()

            assertNull(
                escaped,
                "the contained bootstrap must not reach the uncaught path; was: $escaped",
            )
        }
    }
}
