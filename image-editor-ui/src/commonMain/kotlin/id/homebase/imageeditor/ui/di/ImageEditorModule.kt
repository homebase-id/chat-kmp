package id.homebase.imageeditor.ui.di

import id.homebase.imageeditor.ui.CropEditorViewModel
import id.homebase.imageeditor.ui.CropResultBus
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Koin DI for the cropper. Install once from `homebase-core`'s `AppModule`.
 *
 * - [CropResultBus] is a process-wide singleton — both the caller (e.g.
 *   `ConversationListViewModel`) and the cropper screen need the same bus
 *   instance to exchange the source bytes and the cropped result.
 * - [CropEditorViewModel] is per-screen.
 */
val imageEditorModule = module {
    single { CropResultBus() }
    viewModelOf(::CropEditorViewModel)
}
