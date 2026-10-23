package id.homebase.imageeditor.ui.di

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.toRoute
import id.homebase.core.ui.navigation.Route
import id.homebase.imageeditor.ui.CropEditorViewModel
import id.homebase.imageeditor.ui.CropResultBus
import id.homebase.imageeditor.ui.DrawEditorViewModel
import id.homebase.imageeditor.ui.DrawResultBus
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.uuid.Uuid

/**
 * Koin DI for the cropper and the draw editor. Install once from
 * `homebase-core`'s `AppModule`.
 *
 * - The `*ResultBus` types are process-wide singletons — both the caller
 *   (e.g. `ConversationListViewModel`) and the editor screen need the same
 *   bus instance to exchange source bytes and the resulting image.
 * - Editor ViewModels are per-screen.
 */
val imageEditorModule = module {
    single { CropResultBus() }
    single { DrawResultBus() }
    viewModel {
        val route = get<SavedStateHandle>().toRoute<Route.Crop>()
        CropEditorViewModel(Uuid.parse(route.requestId), route.lockedAspect, get())
    }
    viewModel {
        val route = get<SavedStateHandle>().toRoute<Route.Draw>()
        DrawEditorViewModel(Uuid.parse(route.requestId), get())
    }
}
