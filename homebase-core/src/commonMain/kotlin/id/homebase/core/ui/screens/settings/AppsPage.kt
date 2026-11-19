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
import id.homebase.core.ui.screens.location.LocationAppSettingsScreen
import id.homebase.core.ui.screens.location.LocationViewModel
import id.homebase.core.ui.screens.moments.MomentsSettingsScreen
import id.homebase.core.ui.screens.vault.settings.VaultSettingsScreen
import org.koin.compose.viewmodel.koinViewModel

internal enum class AppsPage { Chat, Photobase, Filesync, Email, Feed, Vault, Location, Moments, Calls, Contacts, WebDrop }

// The route carries the name: enum route arguments have no NavType on iOS and wasmJs.
internal val AppsPage.route: Route.AppSettings get() = Route.AppSettings(name)

internal val Route.AppSettings.appsPage: AppsPage get() = AppsPage.valueOf(page)

internal class AppsPageLinks(
    val onOpenChat: () -> Unit,
    val onOpenFeed: () -> Unit,
    val onOpenMoments: () -> Unit,
    val onOpenVault: () -> Unit,
    val onOpenEmail: () -> Unit,
    val onOpenEmailModeSwitch: () -> Unit,
    val onOpenContacts: () -> Unit,
    val onOpenFilesync: () -> Unit,
    val onOpenPhotobase: () -> Unit,
    val onOpenWebDrop: () -> Unit,
    val onOpenLocation: () -> Unit,
    /** Null while calls are off. */
    val onOpenCalls: (() -> Unit)?,
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
    when (page) {
        AppsPage.Chat -> ChatSettingsScreen(viewModel = koinViewModel(), onBack = onBack, onOpenChat = links.onOpenChat)

        AppsPage.Feed -> FeedSettingsScreen(viewModel = koinViewModel(), onBackClick = onBack, onOpenFeed = links.onOpenFeed)

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

        AppsPage.Filesync -> FilesyncSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onBack,
            onOpenDrive = links.onOpenFilesync,
        )

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

        AppsPage.WebDrop -> WebDropSettingsUi(onBack = onBack, onOpenWebDrop = links.onOpenWebDrop)

        AppsPage.Location -> LocationAppSettingsScreen(
            viewModel = links.locationViewModel,
            onBack = onBack,
            onOpenLocation = links.onOpenLocation,
        )

        AppsPage.Calls -> CallsSettingsScreen(viewModel = koinViewModel(), onBackClick = onBack, onOpenCalls = links.onOpenCalls)

        AppsPage.Photobase -> PhotobaseSettingsScreen(
            viewModel = koinViewModel(viewModelStoreOwner = settingsOwner),
            onBack = onBack,
            backup = links.photoBackup,
            available = links.photobaseAvailable,
            onOpenPhotos = links.onOpenPhotobase.takeIf { !openedFromApp },
        )
    }
}
