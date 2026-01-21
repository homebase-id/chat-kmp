package id.homebase.core.di

import id.homebase.chat.ChatListViewModel
import id.homebase.core.ui.screens.home.HomeViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    viewModelOf(::HomeViewModel)
    viewModelOf(::ChatListViewModel)

//    // ChatMessageDetailViewModel with parameters
//    viewModel { (driveId: Uuid, fileId: Uuid) ->
//        ChatMessageDetailViewModel(
//                driveId = driveId,
//                fileId = fileId,
//                driveFileProvider = getOrNull<DriveFileProvider>()
//        )
//    }
}

/** All Koin modules for the application. */
val allModules = listOf(appModule)
