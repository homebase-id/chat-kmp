package id.homebase.core.di

import id.homebase.api.di.apiModule
import id.homebase.auth.login.LoginViewModel
import id.homebase.chat.ConversationListViewModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.chat.services.convo.ContactService
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.notifications.NotificationService
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.screens.home.HomeViewModel
import id.homebase.core.ui.screens.notifications.NotificationSettingsViewModel
import id.homebase.core.ui.screens.settings.SettingsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    single { UserPreferences(get()) }

    singleOf(::AuthConnectionCoordinator)

    factoryOf(::PayloadBundleEncryptionService)
    singleOf(::ContactService)
    singleOf(::ConversationStream)
    singleOf(::ConversationService)
    singleOf(::ChatMessageStream)
    singleOf(::ChatMessageSenderService)
    singleOf(::HomebaseImageLoader)
    singleOf(::ChatMessageActionService)
    singleOf(::NotificationService)

    viewModelOf(::HomeViewModel)
    viewModelOf(::ConversationListViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::NotificationSettingsViewModel)
    viewModelOf(::LoginViewModel)
}

// Common module that each platform will implement
expect fun platformModule(): Module

/** All Koin modules for the application. */
val allModules = listOf(platformModule(), apiModule, appModule)
