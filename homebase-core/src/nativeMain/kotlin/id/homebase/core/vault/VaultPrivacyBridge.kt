package id.homebase.core.vault

import id.homebase.core.email.EmailPreferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object VaultPrivacyBridge : KoinComponent {
    private val vaultPreferences: VaultPreferences by inject()
    private val emailPreferences: EmailPreferences by inject()

    val shouldProtect: Boolean
        get() = (vaultPreferences.isVaultScreenActive && vaultPreferences.biometricsEnabled.value) ||
                (emailPreferences.isEmailScreenActive && emailPreferences.biometricsEnabled.value)
}
