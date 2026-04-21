package id.homebase.core.ui.screens.vault

import id.homebase.core.vault.ViewMode
import io.github.vinceglb.filekit.PlatformFile

data class VaultUiState(
    val isCheckingPermissions: Boolean = false,
    val isLoading: Boolean = false,
    val files: List<VaultFileItem> = emptyList(),
    val viewMode: ViewMode = ViewMode.List,
    val fullScreenOverlay: VaultOverlay? = null,
)

sealed interface VaultOverlay {
    data class Preview(val file: VaultFileItem) : VaultOverlay
}

sealed interface VaultUiAction {
    data object SetupClicked : VaultUiAction
    data object DismissOnboardingClicked : VaultUiAction
    data class FileSelected(val file: PlatformFile) : VaultUiAction
    data class FileClicked(val file: VaultFileItem) : VaultUiAction
    data class DeleteFile(val file: VaultFileItem) : VaultUiAction
    data class RenameFile(val file: VaultFileItem, val newName: String) : VaultUiAction
    data class ShareFile(val file: VaultFileItem) : VaultUiAction
    data object ToggleViewMode : VaultUiAction
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
