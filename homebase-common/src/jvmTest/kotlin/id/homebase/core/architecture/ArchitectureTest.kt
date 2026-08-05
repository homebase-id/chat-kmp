package id.homebase.core.architecture

import androidx.compose.runtime.Composable
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.functions
import com.lemonappdev.konsist.api.ext.list.withAnnotationOf
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

class ArchitectureTest {
    @Test
    fun `UiState classes should be data classes`() {
        Konsist.scopeFromProject()
            .classes()
            .withNameEndingWith("UiState")
            .assertTrue { it.hasDataModifier }
    }

    @Test
    fun `No hardcoded strings in Composables`() {
        Konsist.scopeFromProject()
            .files
            .filter { !it.hasNameEndingWith("Test") && !it.hasNameEndingWith("Example") && !it.hasNameStartingWith("Developer") }
            .functions()
            .withAnnotationOf(Composable::class)
            .assertFalse {
                it.text.contains(Regex("""Text\s*\(\s*"[^"]*"""")) ||
                        it.text.contains(Regex("""Text\s*\(\s*text\s*=\s*"[^"]*""""))
            }
    }

    /**
     * Single-upload-path guard (#844). Payload encryption for an upload must go through the shared
     * pipeline — `UploadService.upload`/`updateFile` — so the payload-cache/fail-soft/seed policies
     * live in exactly one place. `encryptBundle` is the true "I'm doing a payload upload" signal, so
     * calling it anywhere else is the violation. (The two-request-type rule was unworkable: header-only
     * system records and the outbox rekey plumbing legitimately build UploadFileRequest /
     * UpdateFileByUniqueIdRequest, and a text rule can't tell payload-bearing from header-only.)
     *
     * The regex matches CALLS (`.encryptBundle(`); the interface/impl `fun encryptBundle(` definitions
     * have no leading dot and don't match. `UploadService` is excluded (it owns the one allowed call).
     * Three functions are documented exceptions — each re-encrypts an in-memory text-overflow mixed
     * with already-encrypted payloads (recovery plumbing) or is a hot multi-purpose mutation whose
     * encrypt branch is rare, so routing them through UploadService adds risk without payoff:
     *   - `updateMessage` (chat edit / amend-pending-create), `resendAsCreate` (chat resend),
     *   - `updateConversationInternal` (conversation update: archival/participants/leaveGroup/heal).
     * Adding a NEW `encryptBundle` call anywhere else — or a new media-send function — fails here.
     */
    @Test
    fun `encryptBundle is confined to the shared upload pipeline`() {
        val documentedExceptions = setOf("updateMessage", "resendAsCreate", "updateConversationInternal")
        Konsist.scopeFromProject()
            .files
            .filter { !it.hasNameEndingWith("Test") }
            .filter { !it.hasNameEndingWith("UploadService") }
            .functions()
            .filter { it.name !in documentedExceptions }
            .assertFalse(
                additionalMessage = "Payload encryption for an upload must go through " +
                    "UploadService.upload/updateFile (issue #844), not a direct encryptBundle call. " +
                    "If this is a genuine recovery/plumbing exception, add it to documentedExceptions " +
                    "with a comment explaining why it can't route through UploadService."
            ) {
                it.text.contains(Regex("""\.\s*encryptBundle\s*\("""))
            }
    }

    /**
     * Efficacy guard for the non-blocking login (#1231). The connect sequence must resolve the
     * drive registry from the local index and let [AuthConnectionCoordinator]'s background
     * reconcile do the server half — otherwise every login, warm restores included, queues the
     * WebSocket connect and profile load behind a `getFileHeaderByUid` round-trip.
     *
     * This is a source-level rule because the invariant lives in a call-site ARGUMENT, and the
     * first attempt at this fix passed `deferServerReconcile = !headless` — which is `false` for
     * an ordinary Android launcher launch (`startsHeadless = supportsBackgroundWake`, corrected
     * only once `promoteToForeground()` runs) and so blocked exactly as before. Every unit test
     * still passed against that no-op. Any condition here reintroduces that bug.
     */
    @Test
    fun `AuthConnectionCoordinator never blocks the connect sequence on the registry server fetch`() {
        Konsist.scopeFromProject()
            .files
            .filter { it.hasNameEndingWith("AuthConnectionCoordinator") }
            .assertFalse(
                additionalMessage = "The connect sequence must call " +
                    "driveRegistry.bootstrap(deferServerReconcile = true) unconditionally (issue #1231). " +
                    "A bare bootstrap() or a conditional argument blocks login on a server round-trip; " +
                    "`headless` in particular cannot distinguish a launcher launch from an FCM wake at " +
                    "this point. Callers needing the authoritative set use awaitRegistryReconcile()."
            ) { file ->
                file.text.contains(Regex("""bootstrap\s*\((?!\s*deferServerReconcile\s*=\s*true\s*\))"""))
            }
    }

    @Test
    fun `Do not allow calling close on httpClient`() {
        Konsist.scopeFromProject()
            .files
            .filter { !it.hasNameEndingWith("Test") }
            .assertFalse(
                additionalMessage = "HttpClient is managed by Koin DI and should not be manually closed"
            ) { file ->
                file.text.contains(Regex("""httpClient\s*\.\s*close\s*\("""))
            }
    }
}
