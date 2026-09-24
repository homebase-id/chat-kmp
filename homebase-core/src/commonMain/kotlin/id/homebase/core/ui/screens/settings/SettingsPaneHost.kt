@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.ui.screens.appearance.AppearanceSettingsScreen
import id.homebase.core.ui.screens.card.ProfileCardEditorScreen
import id.homebase.core.ui.screens.card.ProfileCardScreen
import id.homebase.core.ui.screens.card.ProfileCardViewModel
import id.homebase.core.ui.screens.card.StartCardHostWhenSettled
import id.homebase.core.ui.screens.contactbook.settings.ContactBookSettingsScreen
import id.homebase.core.ui.screens.email.settings.EmailSettingsScreen
import id.homebase.core.ui.screens.help.HelpScreen
import id.homebase.core.ui.screens.keyboard.KeyboardSettingsScreen
import id.homebase.core.ui.screens.media.MediaSettingsScreen
import id.homebase.core.ui.screens.moments.MomentsSettingsScreen
import id.homebase.core.ui.screens.notifications.NotificationSettingsScreen
import id.homebase.core.ui.screens.profile.ProfileAvatarEditScreen
import id.homebase.core.ui.screens.profile.ProfileEditScreen
import id.homebase.core.ui.screens.storage.StorageSettingsScreen
import id.homebase.core.ui.screens.vault.settings.VaultSettingsScreen
import id.homebase.core.widget.ProvideSettingsChrome
import id.homebase.resources.MR
import id.homebase.resources.close
import id.homebase.resources.settings
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal data class SettingsPaneActions(
    val onOpenWebDrop: () -> Unit,
    val onLocation: () -> Unit,
    val onOpenMoments: () -> Unit,
    val onOpenVault: () -> Unit,
    val onOpenEmail: () -> Unit,
    val onOpenContacts: () -> Unit,
    val onNavigateToCropper: (Uuid) -> Unit,
    val onNavigateToDeveloperMenu: () -> Unit,
    val onNavigateToDefragmenter: () -> Unit,
)

private enum class ProfilePage { Edit, Avatar, Card, CardEditor }

@Composable
internal fun SettingsPaneHost(
    onDismiss: () -> Unit,
    actions: SettingsPaneActions,
    profileCardEnabled: Boolean,
) {
    // Plain remember, not rememberSaveable: the pane exists only on desktop/web, which have no
    // configuration change or process death to restore across.
    var category by remember { mutableStateOf(SettingsCategory.General) }
    var profilePage by remember { mutableStateOf<ProfilePage?>(null) }
    var cardOpenedFromEdit by remember { mutableStateOf(false) }
    // Pre-warms the card page; its host lives as long as the Settings entry.
    val cardViewModel = if (profileCardEnabled) koinViewModel<ProfileCardViewModel>() else null
    cardViewModel?.let { StartCardHostWhenSettled(it) }

    Column(modifier = Modifier.fillMaxSize()) {
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

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            SettingsSidebar(
                selected = category,
                onSelect = {
                    category = it
                    profilePage = null
                },
            )
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val enterFade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val exitFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            val slide = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            AnimatedContent(
                targetState = category to profilePage,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                transitionSpec = {
                    val from = initialState.second
                    val to = targetState.second
                    when {
                        // The card pages host a native view that neither fades nor can run twice at once.
                        from.isCard() || to.isCard() -> EnterTransition.None togetherWith ExitTransition.None
                        from == to -> fadeIn(enterFade) togetherWith fadeOut(exitFade)
                        else -> {
                            val forward = (to?.ordinal ?: -1) > (from?.ordinal ?: -1)
                            val sign = (if (forward) 1 else -1) * (if (rtl) -1 else 1)
                            (slideInHorizontally(slide) { sign * it / 10 } + fadeIn(enterFade)) togetherWith
                                (slideOutHorizontally(slide) { -sign * it / 10 } + fadeOut(exitFade))
                        }
                    }
                },
            ) { (shownCategory, profile) ->
                if (profile == null) {
                    ProvideSettingsChrome(embedded = true) {
                        CategoryPage(
                            category = shownCategory,
                            onDismiss = onDismiss,
                            onSelectCategory = { category = it },
                            onProfileEdit = { profilePage = ProfilePage.Edit },
                            onProfileAvatarEdit = { profilePage = ProfilePage.Avatar },
                            onProfileCard = {
                                cardOpenedFromEdit = false
                                profilePage = ProfilePage.Card
                            }.takeIf { cardViewModel != null },
                            actions = actions,
                        )
                    }
                } else {
                    // The profile pages are a drill-down inside the detail column, so their own
                    // back arrow is correct here and returns to the category rather than closing.
                    ProvideSettingsChrome(embedded = false) {
                        when (profile) {
                            ProfilePage.Edit -> ProfileEditScreen(
                                viewModel = koinViewModel(),
                                avatarViewModel = koinViewModel(),
                                onBack = { profilePage = null },
                                onNavigateToCropper = actions.onNavigateToCropper,
                                onOpenCard = {
                                    cardOpenedFromEdit = true
                                    profilePage = ProfilePage.Card
                                }.takeIf { cardViewModel != null },
                            )

                            ProfilePage.Avatar -> ProfileAvatarEditScreen(
                                viewModel = koinViewModel(),
                                onBack = { profilePage = null },
                                onNavigateToCropper = actions.onNavigateToCropper,
                            )

                            ProfilePage.Card -> cardViewModel?.let {
                                ProfileCardScreen(
                                    viewModel = it,
                                    onBack = { profilePage = if (cardOpenedFromEdit) ProfilePage.Edit else null },
                                    onEdit = { profilePage = ProfilePage.CardEditor },
                                )
                            }

                            ProfilePage.CardEditor -> cardViewModel?.let {
                                ProfileCardEditorScreen(
                                    viewModel = it,
                                    onBack = { profilePage = ProfilePage.Card },
                                    onEditProfile = {
                                        cardOpenedFromEdit = false
                                        profilePage = ProfilePage.Edit
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ProfilePage?.isCard() = this == ProfilePage.Card || this == ProfilePage.CardEditor

@Composable
private fun CategoryPage(
    category: SettingsCategory,
    onDismiss: () -> Unit,
    onSelectCategory: (SettingsCategory) -> Unit,
    onProfileEdit: () -> Unit,
    onProfileAvatarEdit: () -> Unit,
    onProfileCard: (() -> Unit)?,
    actions: SettingsPaneActions,
) {
    when (category) {
        SettingsCategory.General -> SettingsScreen(
            viewModel = koinViewModel(),
            actions = SettingsActions(
                onBack = onDismiss,
                onNotifications = { onSelectCategory(SettingsCategory.Notifications) },
                onAppearance = { onSelectCategory(SettingsCategory.Appearance) },
                onMedia = { onSelectCategory(SettingsCategory.Media) },
                onKeyboard = { onSelectCategory(SettingsCategory.Keyboard) },
                onStorage = { onSelectCategory(SettingsCategory.Storage) },
                onHelp = { onSelectCategory(SettingsCategory.Help) },
                onMomentsSettings = { onSelectCategory(SettingsCategory.Moments) },
                onVaultSettings = { onSelectCategory(SettingsCategory.Vault) },
                onEmailSettings = { onSelectCategory(SettingsCategory.Email) },
                onOpenWebDrop = actions.onOpenWebDrop,
                onLocation = actions.onLocation,
                onContactBookSettings = { onSelectCategory(SettingsCategory.Contacts) },
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

        SettingsCategory.Moments -> MomentsSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onDismiss,
            onOpenMoments = actions.onOpenMoments,
        )

        SettingsCategory.Vault -> VaultSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onDismiss,
            onOpenVault = actions.onOpenVault,
        )

        SettingsCategory.Email -> EmailSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onDismiss,
            onOpenEmail = actions.onOpenEmail,
        )

        SettingsCategory.Contacts -> ContactBookSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onDismiss,
            onOpenContacts = actions.onOpenContacts,
        )

        SettingsCategory.Storage -> StorageSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = onDismiss,
            onNavigateToDefragmenter = actions.onNavigateToDefragmenter,
        )

        SettingsCategory.Help -> HelpScreen(
            viewModel = koinViewModel(),
            onBackClick = onDismiss,
            onNavigateToDeveloperMenu = actions.onNavigateToDeveloperMenu,
        )
    }
}
