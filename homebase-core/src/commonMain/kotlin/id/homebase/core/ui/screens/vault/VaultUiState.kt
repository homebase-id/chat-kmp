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
    data class Gallery(val file: VaultFileItem, val initialPage: Int = 0) : VaultOverlay
}

sealed interface VaultUiAction {
    data object SetupClicked : VaultUiAction
    data object DismissOnboardingClicked : VaultUiAction

    data class AddSection(val title: String) : VaultUiAction
    data class RenameSection(val section: VaultSectionUiModel, val newTitle: String) : VaultUiAction
    data class DeleteSection(val section: VaultSectionUiModel) : VaultUiAction
    data class MoveSectionUp(val section: VaultSectionUiModel) : VaultUiAction
    data class MoveSectionDown(val section: VaultSectionUiModel) : VaultUiAction

    data class AddEntryToSection(
        val sectionId: Uuid,
        val files: List<PlatformFile>,
    ) : VaultUiAction

    data class AppendPages(
        val file: VaultFileItem,
        val newFiles: List<PlatformFile>,
    ) : VaultUiAction

    data class DeletePage(
        val file: VaultFileItem,
        val payloadKey: String,
    ) : VaultUiAction

    data class UpdateNotes(
        val file: VaultFileItem,
        val notes: String?,
    ) : VaultUiAction

    data class EntryClicked(val file: VaultFileItem) : VaultUiAction
    data class ShareFile(val file: VaultFileItem) : VaultUiAction
    data class SharePage(val file: VaultFileItem, val payloadKey: String) : VaultUiAction
    data class RenameFile(val file: VaultFileItem, val newName: String) : VaultUiAction
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
