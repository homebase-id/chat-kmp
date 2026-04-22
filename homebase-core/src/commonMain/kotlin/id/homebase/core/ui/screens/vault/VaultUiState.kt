package id.homebase.core.ui.screens.vault

import id.homebase.core.ui.screens.vault.model.VaultSectionUiModel
import io.github.vinceglb.filekit.PlatformFile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class VaultUiState(
    val isCheckingPermissions: Boolean = false,
    val isLoading: Boolean = false,
    val sections: List<VaultSectionUiModel> = emptyList(),
    val fullScreenOverlay: VaultOverlay? = null,
)

sealed interface VaultOverlay {
    data class Preview(val file: VaultFileItem) : VaultOverlay
}

sealed interface VaultUiAction {
    data object SetupClicked : VaultUiAction
    data object DismissOnboardingClicked : VaultUiAction

    data class AddSection(val title: String) : VaultUiAction
    data class RenameSection(val section: VaultSectionUiModel, val newTitle: String) : VaultUiAction
    data class DeleteSection(val section: VaultSectionUiModel) : VaultUiAction
    data class MoveSectionUp(val section: VaultSectionUiModel) : VaultUiAction
    data class MoveSectionDown(val section: VaultSectionUiModel) : VaultUiAction

    data class AddEntryToSection(val sectionId: Uuid, val file: PlatformFile) : VaultUiAction
    data class EntryClicked(val file: VaultFileItem) : VaultUiAction
    data class ShareFile(val file: VaultFileItem) : VaultUiAction
    data class DeleteFile(val file: VaultFileItem) : VaultUiAction

    data object CloseOverlay : VaultUiAction
    data object RefreshFiles : VaultUiAction
}

sealed interface VaultUiEvent {
    data object Activated : VaultUiEvent
    data object CloseOnboarding : VaultUiEvent
    data class ShareFileReady(
        val filePath: String,
        val fileName: String,
        val contentType: String,
    ) : VaultUiEvent
    data class Error(val message: String) : VaultUiEvent
}
