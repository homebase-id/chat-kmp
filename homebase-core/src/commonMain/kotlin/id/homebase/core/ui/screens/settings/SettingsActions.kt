package id.homebase.core.ui.screens.settings

/**
 * Every destination the settings hub can reach. No defaults on purpose: a route the
 * navigation graph forgets to supply is a compile error, not a dead row.
 */
data class SettingsActions(
    val onBack: () -> Unit,
    val onApps: () -> Unit,
    val onNotifications: () -> Unit,
    val onAppearance: () -> Unit,
    val onMedia: () -> Unit,
    val onKeyboard: () -> Unit,
    val onStorage: () -> Unit,
    val onAccount: () -> Unit,
    val onHelp: () -> Unit,
    val onProfileEdit: () -> Unit,
    val onProfileAvatarEdit: () -> Unit,
    /** Null while the profile card is dark-launched off, which hides its row. */
    val onProfileCard: (() -> Unit)?,
)

data class AppsSettingsActions(
    val onBack: () -> Unit,
    val onFeedSettings: () -> Unit,
    val onMomentsSettings: () -> Unit,
    val onVaultSettings: () -> Unit,
    val onFilesyncSettings: () -> Unit,
    val onEmailSettings: () -> Unit,
    val onOpenWebDrop: () -> Unit,
    val onLocation: () -> Unit,
    val onContactBookSettings: () -> Unit,
    val onCallsSettings: () -> Unit,
    val onPhotobaseSettings: (() -> Unit)?,
)
