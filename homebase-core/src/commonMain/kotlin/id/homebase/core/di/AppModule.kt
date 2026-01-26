package id.homebase.core.di

import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.api.di.apiModule
import id.homebase.chat.ChatListViewModel
import id.homebase.chat.data.ContactService
import id.homebase.chat.data.ConversationService
import id.homebase.chat.data.MockChatApiProvider
import id.homebase.chat.login.LoginViewModel
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.screens.home.HomeViewModel
import id.homebase.core.ui.screens.settings.SettingsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    single { UserPreferences(get()) }
    single { MockChatApiProvider() }

    singleOf(::AuthConnectionCoordinator)

    singleOf(::ContactService)
    singleOf(::ConversationService)
    singleOf(::ContactService)

    viewModelOf(::HomeViewModel)
    viewModelOf(::ChatListViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::LoginViewModel)

}

// Common module that each platform will implement
expect fun platformModule(): Module

/** All Koin modules for the application. */
val allModules = listOf(platformModule(), apiModule, appModule)
