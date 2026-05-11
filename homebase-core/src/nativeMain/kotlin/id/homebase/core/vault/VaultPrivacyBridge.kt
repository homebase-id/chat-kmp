package id.homebase.core.vault

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object VaultPrivacyBridge : KoinComponent {
    private val vaultPreferences: VaultPreferences by inject()

    val shouldProtect: Boolean
        get() = vaultPreferences.isVaultScreenActive &&
                vaultPreferences.biometricsEnabled.value
}
