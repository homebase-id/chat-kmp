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
import id.homebase.core.ui.screens.location.LocationSettingsScreen
import id.homebase.core.ui.screens.location.LocationViewModel
import id.homebase.core.ui.screens.moments.MomentsSettingsScreen
import id.homebase.core.ui.screens.vault.settings.VaultSettingsScreen
import org.koin.compose.viewmodel.koinViewModel

internal enum class AppsPage { Chat, Photobase, Filesync, Email, Feed, Vault, Location, Moments, Calls, Contacts, WebDrop }

// The route carries the name: enum route arguments have no NavType on iOS and wasmJs.
internal val AppsPage.route: Route.AppSettings get() = Route.AppSettings(name)

internal val Route.AppSettings.appsPage: AppsPage get() = AppsPage.valueOf(page)

/** The app each page belongs to: its Open row leads there, and from there the row is left out. */
internal val AppsPage.appRoute: Route
    get() = when (this) {
        AppsPage.Chat -> Route.ChatList
        AppsPage.Photobase -> Route.Photobase
        AppsPage.Filesync -> Route.Filesync
        AppsPage.Email -> Route.Email
        AppsPage.Feed -> Route.Feed
        AppsPage.Vault -> Route.Vault
        AppsPage.Location -> Route.Location
        AppsPage.Moments -> Route.Moments
        AppsPage.Calls -> Route.Calls
        AppsPage.Contacts -> Route.ContactBook
        AppsPage.WebDrop -> Route.WebDrop
    }

internal class AppsPageLinks(
    val onOpenApp: (AppsPage) -> Unit,
    val onOpenEmailModeSwitch: () -> Unit,
    /** False while calls are off: there's nothing to open. */
    val callsAvailable: Boolean,
    /** False while Photobase is dark-launched off. */
    val photobaseAvailable: Boolean,
    val photoBackup: PhotoBackupLinks?,
    // The app's own instance: a second one would run its own verify loop.
    val locationViewModel: LocationViewModel,
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
    val open: (() -> Unit)? = { links.onOpenApp(page) }
        .takeIf { !openedFromApp && (page != AppsPage.Calls || links.callsAvailable) }
    when (page) {
        AppsPage.Chat -> ChatSettingsScreen(viewModel = koinViewModel(), onBack = onBack, onOpenChat = open)

        AppsPage.Feed -> FeedSettingsScreen(viewModel = koinViewModel(), onBackClick = onBack, onOpenFeed = open)

        AppsPage.Moments -> MomentsSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onBack,
            onOpenMoments = open,
        )

        AppsPage.Vault -> VaultSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onBack,
            onOpenVault = open,
        )

        AppsPage.Filesync -> FilesyncSettingsScreen(viewModel = koinViewModel(), onBackClick = onBack, onOpenDrive = open)

        AppsPage.Email -> EmailSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onBack,
            onOpenEmail = open,
            mailboxMode = emailMailboxMode,
            onChangeMode = links.onOpenEmailModeSwitch,
        )

        AppsPage.Contacts -> ContactBookSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onBack,
            onOpenContacts = open,
        )

        AppsPage.WebDrop -> WebDropSettingsUi(onBack = onBack, onOpenWebDrop = open)

        AppsPage.Location -> LocationSettingsScreen(
            viewModel = links.locationViewModel,
            onNavigateBack = onBack,
            onOpenLocation = open,
        )

        AppsPage.Calls -> CallsSettingsScreen(viewModel = koinViewModel(), onBackClick = onBack, onOpenCalls = open)

        AppsPage.Photobase -> if (links.photobaseAvailable) {
            PhotobaseSettingsScreen(
                viewModel = koinViewModel(viewModelStoreOwner = settingsOwner),
                onBack = onBack,
                backup = links.photoBackup,
                onOpenPhotos = open,
            )
        } else {
            PhotobaseInTestingUi(onBack)
        }
    }
}
