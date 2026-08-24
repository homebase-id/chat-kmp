package id.homebase.core.ui.screens.settings

/**
 * Every destination the settings hub can reach. No defaults on purpose: a route the
 * navigation graph forgets to supply is a compile error, not a dead row.
 */
data class SettingsActions(
    val onBack: () -> Unit,
    val onNotifications: () -> Unit,
    val onAppearance: () -> Unit,
    val onStorage: () -> Unit,
    val onHelp: () -> Unit,
    val onMomentsSettings: () -> Unit,
    val onVaultSettings: () -> Unit,
    val onLocation: () -> Unit,
    val onContactBookSettings: () -> Unit,
    val onProfileEdit: () -> Unit,
    val onProfileAvatarEdit: () -> Unit,
)
