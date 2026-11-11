package id.homebase.core.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import id.homebase.api.client.mail.MailboxMode
import id.homebase.core.ui.navigation.Route
import id.homebase.core.ui.screens.calls.settings.CallsSettingsScreen
import id.homebase.core.ui.screens.contactbook.settings.ContactBookSettingsScreen
import id.homebase.core.ui.screens.email.settings.EmailSettingsScreen
import id.homebase.core.ui.screens.feed.settings.FeedSettingsScreen
import id.homebase.core.ui.screens.filesync.settings.FilesyncSettingsScreen
import id.homebase.core.ui.screens.moments.MomentsSettingsScreen
import id.homebase.core.ui.screens.vault.settings.VaultSettingsScreen
import org.koin.compose.viewmodel.koinViewModel

internal enum class AppsPage { Feed, Moments, Vault, Filesync, Email, Contacts, Calls, Photobase }

// The route carries the name: enum route arguments have no NavType on iOS and wasmJs.
internal val AppsPage.route: Route.AppSettings get() = Route.AppSettings(name)

internal val Route.AppSettings.appsPage: AppsPage get() = AppsPage.valueOf(page)

internal class AppsPageLinks(
    val onOpenMoments: () -> Unit,
    val onOpenVault: () -> Unit,
    val onOpenEmail: () -> Unit,
    val onOpenEmailModeSwitch: () -> Unit,
    val onOpenContacts: () -> Unit,
    val photoBackup: PhotoBackupLinks?,
)

/**
 * [openedFromApp]: the page was pushed over its own app, whose "Open" button would only lead back.
 * [settingsOwner] holds the hub's view model, which the Photobase page shares.
 */
@Composable
internal fun AppsPageContent(
    page: AppsPage,
    onBack: () -> Unit,
    links: AppsPageLinks,
    emailMailboxMode: MailboxMode?,
    openedFromApp: Boolean = false,
    settingsOwner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current),
) {
    when (page) {
        AppsPage.Feed -> FeedSettingsScreen(viewModel = koinViewModel(), onBackClick = onBack)

        AppsPage.Moments -> MomentsSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onBack,
            onOpenMoments = links.onOpenMoments,
        )

        AppsPage.Vault -> VaultSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onBack,
            onOpenVault = links.onOpenVault,
            showOpenVault = !openedFromApp,
        )

        AppsPage.Filesync -> FilesyncSettingsScreen(viewModel = koinViewModel(), onBackClick = onBack)

        AppsPage.Email -> EmailSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onBack,
            onOpenEmail = links.onOpenEmail,
            mailboxMode = emailMailboxMode,
            onChangeMode = links.onOpenEmailModeSwitch,
        )

        AppsPage.Contacts -> ContactBookSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onBack,
            onOpenContacts = links.onOpenContacts,
            showOpenContacts = !openedFromApp,
        )

        AppsPage.Calls -> CallsSettingsScreen(viewModel = koinViewModel(), onBackClick = onBack)

        AppsPage.Photobase -> PhotobaseSettingsScreen(
            viewModel = koinViewModel(viewModelStoreOwner = settingsOwner),
            onBack = onBack,
            backup = links.photoBackup,
        )
    }
}
