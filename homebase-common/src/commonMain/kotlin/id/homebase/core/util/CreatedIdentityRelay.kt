package id.homebase.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The domain of an identity just created in the sign-up flow, on its way to the login screen.
 *
 * Written by whichever platform catches the browser coming back — the WebView on Android,
 * `onOpenURL` on iOS — and read by LoginViewModel, which owns the field it prefills. A relay
 * rather than a callback registration because the login screen may not exist yet when the
 * browser returns.
 *
 * Reachable by any app on the device that fires the deep link, so treat the value as untrusted:
 * the reader validates it before showing it.
 */
object CreatedIdentityRelay {
    private val _domain = MutableStateFlow<String?>(null)
    val domain: StateFlow<String?> = _domain

    fun deliver(domain: String) {
        _domain.value = domain
    }

    fun consume() {
        _domain.value = null
    }
}
