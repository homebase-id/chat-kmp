package id.homebase.core.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.onboarding.OnboardingInfo
import id.homebase.api.common.OdinId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Offers, once for the whole identity, to connect with whoever invited it (#1908). The onboarding info is
 * marked handled before the prompt shows, so a failure there means no prompt rather than a repeated one.
 */
class InvitedByPromptViewModel(
    private val loadOnboarding: suspend () -> OnboardingInfo,
    private val markHandled: suspend () -> Unit,
) : ViewModel() {

    private val _invitedBy = MutableStateFlow<OdinId?>(null)

    /** Who to offer a connection to; null when there is nothing to offer. */
    val invitedBy: StateFlow<OdinId?> = _invitedBy.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val info = loadOnboarding()
                val inviter = info.invitedBy
                if (info.handledAt == null && inviter != null) {
                    markHandled()
                    _invitedBy.value = OdinId(inviter)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "Onboarding prompt skipped" }
            }
        }
    }

    fun onPromptShown() {
        _invitedBy.value = null
    }

    private companion object {
        const val TAG = "InvitedByPromptViewModel"
    }
}
