@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.ui.screens.account.AccountSettingsScreen
import id.homebase.core.ui.screens.appearance.AppearanceSettingsScreen
import id.homebase.core.ui.screens.card.ProfileCardEditorScreen
import id.homebase.core.ui.screens.card.ProfileCardScreen
import id.homebase.core.ui.screens.card.ProfileCardViewModel
import id.homebase.core.ui.screens.card.StartCardHostWhenSettled
import id.homebase.core.ui.screens.help.HelpScreen
import id.homebase.core.ui.screens.keyboard.KeyboardSettingsScreen
import id.homebase.core.ui.screens.media.MediaSettingsScreen
import id.homebase.core.ui.screens.notifications.NotificationSettingsScreen
import id.homebase.core.ui.screens.profile.ProfileAvatarEditScreen
import id.homebase.core.ui.screens.storage.StorageSettingsScreen
import id.homebase.core.widget.ProvideSettingsChrome
import id.homebase.core.ui.navigation.LocalPaneFullWindow
import id.homebase.core.widget.rememberTabSlideFade
import id.homebase.resources.MR
import id.homebase.resources.close
import id.homebase.resources.settings
import org.jetbrains.compose.resources.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.compose.viewmodel.koinViewModel
import id.homebase.api.client.mail.MailboxMode
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal data class SettingsPaneActions(
    val onOpenWebDrop: () -> Unit,
    val onLocation: () -> Unit,
    val appsPageLinks: AppsPageLinks,
    val photobaseAvailable: Boolean = false,
    val onNavigateToCropper: (Uuid) -> Unit,
    val onNavigateToDeveloperMenu: () -> Unit,
    val onNavigateToDefragmenter: () -> Unit,
)

private enum class ProfilePage { Avatar, Card, CardEditor }

@Composable
internal fun SettingsPaneHost(
    onDismiss: () -> Unit,
    actions: SettingsPaneActions,
    profileCardEnabled: Boolean,
    emailMailboxMode: MailboxMode?,
) {
    // Plain remember, not rememberSaveable: the pane exists only on desktop/web, which have no
    // configuration change or process death to restore across.
    val requested = remember { SettingsPaneRequest.take() }
    var category by remember { mutableStateOf(requested?.category ?: SettingsCategory.General) }
    var appsPage by remember { mutableStateOf(requested?.appsPage) }
    var profilePage by remember { mutableStateOf(if (requested?.profileCard == true) ProfilePage.Card else null) }
    var editorOpenedFromCard by remember { mutableStateOf(false) }
    var editorOpensDesign by remember { mutableStateOf(false) }
    val openEditor = { fromCard: Boolean, design: Boolean ->
        editorOpenedFromCard = fromCard
        editorOpensDesign = design
        profilePage = ProfilePage.CardEditor
    }
    // Pre-warms the card page; its host lives as long as the Settings entry.
    val cardViewModel = if (profileCardEnabled) koinViewModel<ProfileCardViewModel>() else null
    cardViewModel?.let { StartCardHostWhenSettled(it) }
    val cardViewing by remember(cardViewModel) {
        cardViewModel?.uiState?.map { it.viewing }?.distinctUntilChanged() ?: flowOf(false)
    }.collectAsStateWithLifecycle(false)

    // A viewed card takes the whole dialog and its expand button the window; the editors always take the window.
    val viewerOpen = profilePage == ProfilePage.Card && cardViewing
    var cardExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(viewerOpen) { if (!viewerOpen) cardExpanded = false }
    val paneFullWindow = LocalPaneFullWindow.current
    val editorOpen = profilePage == ProfilePage.CardEditor || profilePage == ProfilePage.Avatar
    val fullWindow = paneFullWindow != null && (editorOpen || (viewerOpen && cardExpanded))
    DisposableEffect(paneFullWindow, fullWindow) {
        paneFullWindow?.value = fullWindow
        onDispose { paneFullWindow?.value = false }
    }

    val cardOpen = profilePage != null
    Column(modifier = Modifier.fillMaxSize()) {
        if (!cardOpen) SettingsPaneHeader(onDismiss)

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (!cardOpen) {
                SettingsSidebar(
                    selected = category,
                    onSelect = {
                        category = it
                        profilePage = null
                        appsPage = null
                    },
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            val enterFade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val exitFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            val tabSlideFade = rememberTabSlideFade()
            AnimatedContent(
                targetState = Triple(category, profilePage, appsPage),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                transitionSpec = {
                    val from = initialState.second
                    val to = targetState.second
                    when {
                        // The card pages host a native view that neither fades nor can run twice at once.
                        from.isCard() || to.isCard() -> EnterTransition.None togetherWith ExitTransition.None
                        from == to && initialState.third != targetState.third ->
                            tabSlideFade.transform(targetState.third != null)
                        from == to -> fadeIn(enterFade) togetherWith fadeOut(exitFade)
                        else -> tabSlideFade.transform((to?.ordinal ?: -1) > (from?.ordinal ?: -1))
                    }
                },
            ) { (shownCategory, profile, shownAppsPage) ->
                if (profile == null) {
                    // An app's page is a drill-down like the profile pages, so it keeps its back arrow.
                    ProvideSettingsChrome(embedded = shownAppsPage == null) {
                        CategoryPage(
                            category = shownCategory,
                            appsPage = shownAppsPage,
                            onDismiss = onDismiss,
                            onSelectCategory = { category = it },
                            onSelectAppsPage = { appsPage = it },
                            onProfileEdit = { openEditor(false, false) },
                            onProfileAvatarEdit = { profilePage = ProfilePage.Avatar },
                            onProfileCard = { profilePage = ProfilePage.Card }.takeIf { cardViewModel != null },
                            actions = actions,
                            emailMailboxMode = emailMailboxMode,
                        )
                    }
                } else {
                    // The profile pages are a drill-down inside the detail column, so their own
                    // back arrow is correct here and returns to the category rather than closing.
                    ProvideSettingsChrome(embedded = false) {
                        when (profile) {
                            ProfilePage.Avatar -> ProfileAvatarEditScreen(
                                viewModel = koinViewModel(),
                                onBack = { profilePage = null },
                                onNavigateToCropper = actions.onNavigateToCropper,
                            )

                            ProfilePage.Card -> cardViewModel?.let {
                                ProfileCardScreen(
                                    viewModel = it,
                                    onBack = { profilePage = null },
                                    onEdit = { openEditor(true, false) },
                                    onChangeDesign = { openEditor(true, true) },
                                    fullWindow = cardExpanded,
                                    onToggleFullWindow = { cardExpanded = !cardExpanded }.takeIf { paneFullWindow != null },
                                )
                            }

                            // Without the card flag this is still Edit profile, so its view model is made here when needed.
                            ProfilePage.CardEditor -> ProfileCardEditorScreen(
                                viewModel = cardViewModel ?: koinViewModel(),
                                avatarViewModel = koinViewModel(),
                                onBack = { profilePage = if (editorOpenedFromCard) ProfilePage.Card else null },
                                onNavigateToCropper = actions.onNavigateToCropper,
                                cardsEnabled = profileCardEnabled,
                                openDesign = editorOpensDesign,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsPaneHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Dimens.Spacing.gutter,
                end = Dimens.Spacing.item,
                top = Dimens.Spacing.item,
                bottom = Dimens.Spacing.item,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(MR.string.settings),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(MR.string.close),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun ProfilePage?.isCard() = this == ProfilePage.Card || this == ProfilePage.CardEditor

@Composable
private fun CategoryPage(
    category: SettingsCategory,
    appsPage: AppsPage?,
    onDismiss: () -> Unit,
    onSelectCategory: (SettingsCategory) -> Unit,
    onSelectAppsPage: (AppsPage?) -> Unit,
    onProfileEdit: () -> Unit,
    onProfileAvatarEdit: () -> Unit,
    onProfileCard: (() -> Unit)?,
    actions: SettingsPaneActions,
    emailMailboxMode: MailboxMode?,
) {
    when (category) {
        SettingsCategory.General -> SettingsScreen(
            viewModel = koinViewModel(),
            actions = SettingsActions(
                onBack = onDismiss,
                onApps = { onSelectCategory(SettingsCategory.Apps) },
                onNotifications = { onSelectCategory(SettingsCategory.Notifications) },
                onAppearance = { onSelectCategory(SettingsCategory.Appearance) },
                onMedia = { onSelectCategory(SettingsCategory.Media) },
                onKeyboard = { onSelectCategory(SettingsCategory.Keyboard) },
                onStorage = { onSelectCategory(SettingsCategory.Storage) },
                onAccount = { onSelectCategory(SettingsCategory.Account) },
                onHelp = { onSelectCategory(SettingsCategory.Help) },
                onProfileEdit = onProfileEdit,
                onProfileAvatarEdit = onProfileAvatarEdit,
                onProfileCard = onProfileCard,
            ),
        )

        SettingsCategory.Notifications -> NotificationSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onDismiss,
        )

        SettingsCategory.Appearance -> AppearanceSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onDismiss,
        )

        SettingsCategory.Media -> MediaSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onDismiss,
        )

        SettingsCategory.Keyboard -> KeyboardSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onDismiss,
        )

        SettingsCategory.Apps -> AppsCategoryPage(
            appsPage = appsPage,
            root = {
                AppsSettingsScreen(
                    actions = AppsSettingsActions(
                        onBack = onDismiss,
                        onOpenPage = onSelectAppsPage,
                        onOpenWebDrop = actions.onOpenWebDrop,
                        onLocation = actions.onLocation,
                        photobaseAvailable = actions.photobaseAvailable,
                    ),
                )
            },
            page = { shown ->
                AppsPageContent(
                    page = shown,
                    onBack = { onSelectAppsPage(null) },
                    links = actions.appsPageLinks,
                    emailMailboxMode = emailMailboxMode,
                )
            },
        )

        SettingsCategory.Storage -> StorageSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onDismiss,
            onNavigateToDefragmenter = actions.onNavigateToDefragmenter,
            onOpenAccount = { onSelectCategory(SettingsCategory.Account) },
        )

        // The hub (General) and this page share the pane's SettingsViewModel, for log out and delete account
        SettingsCategory.Account -> AccountSettingsScreen(
            viewModel = koinViewModel(),
            settingsViewModel = koinViewModel(),
            onBackClick = onDismiss,
        )

        SettingsCategory.Help -> HelpScreen(
            viewModel = koinViewModel(),
            onBackClick = onDismiss,
            onNavigateToDeveloperMenu = actions.onNavigateToDeveloperMenu,
        )
    }
}

@Composable
internal fun AppsCategoryPage(
    appsPage: AppsPage?,
    root: @Composable () -> Unit,
    page: @Composable (AppsPage) -> Unit,
) {
    if (appsPage == null) root() else page(appsPage)
}
