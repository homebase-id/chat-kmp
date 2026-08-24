package id.homebase.core.ui.screens.email.clients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.email.EmailPreferences
import id.homebase.core.email.MailClientDescriptor
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Remembers which mail app the user reads mail in. Device-local on purpose: it reflects what is
 * installed here, and a phone and a laptop will not agree.
 */
class EmailClientPickerViewModel(
    private val emailPreferences: EmailPreferences,
) : ViewModel() {

    val selectedClientId: StateFlow<String?> = emailPreferences.selectedMailClientId

    fun select(client: MailClientDescriptor) {
        viewModelScope.launch { emailPreferences.setSelectedMailClientId(client.id) }
    }
}
