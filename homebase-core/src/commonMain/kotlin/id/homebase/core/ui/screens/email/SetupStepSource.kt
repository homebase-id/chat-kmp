package id.homebase.core.ui.screens.email

import id.homebase.core.ui.screens.email.setup.EmailSetupStep
import id.homebase.core.ui.screens.email.setup.resolveSetupStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The setup step, as a flow for the screen and as [current] for setup's advance check.
 *
 * The check must not read [step].value: a stateIn value is a dispatch behind its inputs whenever
 * the reader was itself resumed on the main dispatcher by another coroutine, and then the step
 * that just completed still looks current.
 */
internal class SetupStepSource(
    scope: CoroutineScope,
    private val uiState: StateFlow<EmailUiState>,
    private val permissionsGranted: StateFlow<Boolean>,
) {
    fun current(): EmailSetupStep = stepOf(uiState.value, permissionsGranted.value)

    val step: StateFlow<EmailSetupStep> = combine(uiState, permissionsGranted, ::stepOf)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), EmailSetupStep.NeedsPermissions)

    private fun stepOf(state: EmailUiState, granted: Boolean): EmailSetupStep = resolveSetupStep(
        hasPermissions = granted,
        driveActivated = state.driveActivated == true,
        status = state.serverStatus,
        credentialCount = state.credentialCount,
    )
}
