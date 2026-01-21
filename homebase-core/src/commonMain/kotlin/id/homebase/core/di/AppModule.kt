package id.homebase.core.di

import id.homebase.chat.ChatListViewModel
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.screens.home.HomeViewModel
import id.homebase.core.ui.screens.settings.SettingsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    single { UserPreferences(get()) }

    viewModelOf(::HomeViewModel)
    viewModelOf(::ChatListViewModel)
    viewModelOf(::SettingsViewModel)



//    // ChatMessageDetailViewModel with parameters
//    viewModel { (driveId: Uuid, fileId: Uuid) ->
//        ChatMessageDetailViewModel(
//                driveId = driveId,
//                fileId = fileId,
//                driveFileProvider = getOrNull<DriveFileProvider>()
//        )
//    }
}

// Common module that each platform will implement
expect fun platformModule(): Module

/** All Koin modules for the application. */
val allModules = listOf(platformModule(), appModule)
