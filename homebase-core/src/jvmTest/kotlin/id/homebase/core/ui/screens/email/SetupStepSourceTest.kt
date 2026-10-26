package id.homebase.core.ui.screens.email

import id.homebase.api.client.mail.MailAppStatus
import id.homebase.api.client.mail.MailboxMode
import id.homebase.core.ui.screens.email.setup.EmailSetupStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SetupStepSourceTest {

    private fun status(mailbox: Boolean) = MailAppStatus(
        tenantMailEnabled = true,
        driveProvisioned = true,
        mailboxProvisioned = mailbox,
        mode = MailboxMode.Encrypted,
    )

    // Unconfined, like Dispatchers.Main.immediate: a resume from another coroutine on the same
    // thread runs the resumed one inside an event loop that queues every other resumption.
    @Test
    fun currentStepSeesAStatusWrittenByACoroutineResumedOnTheSameDispatcher() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val uiState = MutableStateFlow(EmailUiState(driveActivated = true, serverStatus = status(mailbox = false)))
        val source = SetupStepSource(scope, uiState, MutableStateFlow(true))
        scope.launch { source.step.collect { } }
        assertEquals(EmailSetupStep.NeedsMailbox, source.step.value)

        val response = CompletableDeferred<MailAppStatus>()
        val observed = CompletableDeferred<Pair<EmailSetupStep, EmailSetupStep>>()
        scope.launch {
            val fresh = response.await()
            uiState.update { it.copy(serverStatus = fresh) }
            observed.complete(source.step.value to source.current())
        }
        scope.launch { response.complete(status(mailbox = true)) }

        val (flowValue, current) = observed.await()
        assertEquals(EmailSetupStep.NeedsKey, current)
        // What the advance check used to read: still the step that just completed.
        assertEquals(EmailSetupStep.NeedsMailbox, flowValue)
        assertEquals(EmailSetupStep.NeedsKey, source.step.value)
        scope.cancel()
    }
}
