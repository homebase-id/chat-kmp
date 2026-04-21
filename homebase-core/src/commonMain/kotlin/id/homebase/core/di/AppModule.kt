package id.homebase.core.di

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.di.apiModule
import id.homebase.api.sync.DriveSyncManager
import id.homebase.auth.login.LoginViewModel
import id.homebase.chat.addgroupmembers.AddGroupMembersViewModel
import id.homebase.chat.archivedconversations.ArchivedConversationsViewModel
import id.homebase.chat.contactinfo.ContactInfoViewModel
import id.homebase.chat.conversationlist.ConversationListViewModel
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.chat.conversationsettings.ConversationSettingsViewModel
import id.homebase.chat.createconversation.CreateConversationViewModel
import id.homebase.chat.createconversationgroup.CreateConversationGroupViewModel
import id.homebase.chat.data.ConversationState
import id.homebase.chat.editconversationgroup.EditConversationGroupViewModel
import id.homebase.chat.groupsettings.GroupSettingsViewModel
import id.homebase.chat.messageinfo.MessageInfoViewModel
import id.homebase.chat.selectmembers.SelectMembersViewModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.chat.services.ShareSuggestionDonor
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.contact.ConnectionCacheRepository
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.convo.contact.DriveContactService
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.chat.services.requests.ConnectionRequestService
import id.homebase.core.NotificationActionBridge
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.activeSyncLabeledDrives
import id.homebase.core.vault.VaultPreferences
import id.homebase.core.ui.screens.vault.VaultSettingsViewModel
import id.homebase.core.ui.screens.vault.VaultViewModel
import id.homebase.core.connections.ConnectRequestViewModel
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.notifications.NotificationService
import id.homebase.core.settings.UserPreferences
import id.homebase.core.share.ShareContentProcessor
import id.homebase.core.share.ShareConversationCacheWriter
import id.homebase.core.sync.BackgroundSyncOrchestrator
import id.homebase.core.ui.navigation.AppViewModel
import id.homebase.core.ui.screens.appearance.AppearanceSettingsViewModel
import id.homebase.core.ui.screens.connections.ConnectionsViewModel
import id.homebase.core.ui.screens.desktop.DesktopViewModel
import id.homebase.core.ui.screens.help.HelpViewModel
import id.homebase.core.ui.screens.feed.FeedViewModel
import id.homebase.core.ui.screens.home.HomeViewModel
import id.homebase.core.ui.screens.loading.AppLoadingViewModel
import id.homebase.core.ui.screens.notifications.NotificationSettingsViewModel
import id.homebase.core.ui.screens.settings.SettingsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import id.homebase.core.config.getPermissionExtensionConfig
import id.homebase.core.config.getVaultPermissionExtensionConfig

val VaultPermissionQualifier = named("vaultPermission")

val appModule = module {
    single { UserPreferences(get()) }
    single { VaultPreferences(get()) }

    single {
        val vaultPrefs = get<VaultPreferences>()
        val drives = activeSyncLabeledDrives(includeVault = vaultPrefs.activated.value)
        DriveSyncManager(get(), get(), get(), get(), get(),
            drives.associate { it.drive.alias to it.label })
    }

    single {
        AuthConnectionCoordinator(
            credentialsManager = get(),
            ownerSessionRepository = get(),
            youAuthFlowManager = get(),
            driveSyncManager = get(),
            outboxSync = get(),
            eventBus = get(),
            databaseManager = get(),
            vaultPreferences = get(),
            onPostAuthenticated = {
                // Preload conversations and contacts from local DB while navigation
                // and Compose composition are still in progress, saving ~800ms.
                val conversationStream = get<ConversationStream>()
                conversationStream.start()
                get<ContactService>().start()

                // Let ChatMessageStream skip messages for left conversations
                get<ChatMessageStream>().isConversationLeft = { conversationId ->
                    conversationStream.getConversationById(conversationId)
                        ?.conversationState.let { it == ConversationState.Left || it == ConversationState.Removed }
                }

                // region Recovery: missing or deleted conversation file
                val conversationService = get<ConversationService>()
                conversationStream.onRecoverConversation = { conversationId, originalAuthor ->
                    conversationService.recoverConversation(conversationId, originalAuthor)
                }
                // endregion

                // region Auto-unarchive: incoming message for archived conversation
                conversationStream.onUnarchiveConversation = { conversationId ->
                    conversationService.unarchiveConversation(conversationId)
                }
                // endregion
            }
        )
    }
    singleOf(::BackgroundSyncOrchestrator)

    factoryOf(::PayloadBundleEncryptionService)
    factoryOf(::OptimisticWriter)

    singleOf(::ShareConversationCacheWriter)
    singleOf(::ShareContentProcessor)
    singleOf(::LocalAttachmentContextStore)

    singleOf(::ConnectionCacheRepository)
    singleOf(::ConnectionService)
    singleOf(::DriveContactService)
    singleOf(::ContactService)
    singleOf(::ConversationStream)
    singleOf(::ConversationService)
    singleOf(::ChatMessageStream)
    singleOf(::ShareSuggestionDonor)
    singleOf(::ChatMessageSenderService)
    singleOf(::HomebaseImageLoader)
    singleOf(::ChatMessageActionService)
    singleOf(::NotificationService)
    singleOf(::ConnectionRequestService)
    singleOf(::NotificationActionBridge)

    viewModelOf(::AppViewModel)
    viewModelOf(::AppLoadingViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::FeedViewModel)
    viewModelOf(::ConversationListViewModel)
    viewModelOf(::ArchivedConversationsViewModel)
    viewModelOf(::CreateConversationViewModel)
    viewModelOf(::CreateConversationGroupViewModel)
    viewModelOf(::SelectMembersViewModel)
    viewModelOf(::MessageInfoViewModel)
    viewModelOf(::ContactInfoViewModel)
    viewModelOf(::ConversationSettingsViewModel)
    viewModelOf(::GroupSettingsViewModel)
    viewModelOf(::AddGroupMembersViewModel)
    viewModelOf(::EditConversationGroupViewModel)
    viewModel { ExtendPermissionViewModel(get(), get(), get(), getPermissionExtensionConfig()) }
    viewModel(VaultPermissionQualifier) { ExtendPermissionViewModel(get(), get(), get(), getVaultPermissionExtensionConfig()) }
    viewModelOf(::SettingsViewModel)
    viewModelOf(::NotificationSettingsViewModel)
    viewModelOf(::AppearanceSettingsViewModel)
    viewModelOf(::HelpViewModel)
    viewModelOf(::ConnectionsViewModel)
    viewModelOf(::ConnectRequestViewModel)
    viewModelOf(::LoginViewModel)
    viewModelOf(::DesktopViewModel)
    viewModel { VaultViewModel(get(), get(VaultPermissionQualifier)) }
    viewModelOf(::VaultSettingsViewModel)
}

// Common module that each platform will implement
expect fun platformModule(): Module

/** All Koin modules for the application. */
val allModules = listOf(platformModule(), apiModule, appModule)
