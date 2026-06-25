package id.homebase.core.ui.screens.vault

import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.ui.screens.vault.model.VaultSection
import io.github.vinceglb.filekit.PlatformFile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class VaultUiState(
    val isCheckingPermissions: Boolean = false,
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val sections: List<VaultSection> = emptyList(),
    val fullScreenOverlay: VaultOverlay? = null,
)

sealed interface VaultOverlay {
    data class Gallery(val file: VaultEntry, val initialPage: Int = 0) : VaultOverlay
}

enum class VaultEditorTool { Crop, Draw }

sealed interface VaultUiAction {
    data object SetupClicked : VaultUiAction
    data object DismissOnboardingClicked : VaultUiAction

    data class AddSection(val title: String) : VaultUiAction
    data class RenameSection(val section: VaultSection, val newTitle: String) : VaultUiAction
    data class DeleteSection(val section: VaultSection) : VaultUiAction
    data class MoveSectionUp(val section: VaultSection) : VaultUiAction
    data class MoveSectionDown(val section: VaultSection) : VaultUiAction

    data class AddEntryToSection(
        val sectionId: Uuid,
        val files: List<PlatformFile>,
    ) : VaultUiAction

    data class AppendPages(
        val file: VaultEntry,
        val newFiles: List<PlatformFile>,
    ) : VaultUiAction

    /** Route a freshly-picked single image through the editor before it lands
     *  in the existing add/append path. [tool] picks crop vs draw. When
     *  [appendTo] is non-null the edited image is appended to that entry;
     *  otherwise it starts a new entry in [sectionId]. */
    data class EditPickedImageThenAdd(
        val file: PlatformFile,
        val tool: VaultEditorTool,
        val sectionId: Uuid?,
        val appendTo: VaultEntry?,
    ) : VaultUiAction

    /** Re-edit an already-stored image page; the edited bytes replace the
     *  payload at [payloadKey] in place. */
    data class EditExistingPage(
        val file: VaultEntry,
        val payloadKey: String,
        val tool: VaultEditorTool,
    ) : VaultUiAction

    data class DeletePage(
        val file: VaultEntry,
        val payloadKey: String,
    ) : VaultUiAction

    data class UpdateNotes(
        val file: VaultEntry,
        val notes: String?,
    ) : VaultUiAction

    data class UpdateLabel(
        val file: VaultEntry,
        val label: String?,
    ) : VaultUiAction

    data class MoveEntryToSection(
        val entry: VaultEntry,
        val targetSectionId: Uuid,
    ) : VaultUiAction

    data class EntryClicked(val file: VaultEntry) : VaultUiAction
    data class ShareFile(val file: VaultEntry) : VaultUiAction
    data class SharePage(val file: VaultEntry, val payloadKey: String) : VaultUiAction
    data class SavePage(val file: VaultEntry, val payloadKey: String) : VaultUiAction
    data class RenameFile(val file: VaultEntry, val newName: String) : VaultUiAction
    data class DeleteFile(val file: VaultEntry) : VaultUiAction
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
    data class SaveFileReady(
        val filePath: String,
        val fileName: String,
    ) : VaultUiEvent
    data class Error(val error: VaultError) : VaultUiEvent
    data class OpenNoteEditor(val sectionId: Uuid, val entryId: Uuid? = null) : VaultUiEvent
    data class NavigateToCropper(val requestId: Uuid) : VaultUiEvent
    data class NavigateToDrawer(val requestId: Uuid) : VaultUiEvent
}

sealed interface VaultError {
    data object CreateSectionFailed : VaultError
    data object RenameSectionFailed : VaultError
    data object DeleteSectionFailed : VaultError
    data class UploadFailed(val fileName: String) : VaultError
    data object DownloadFailed : VaultError
    data class RenameFileFailed(val fileName: String) : VaultError
    data class DeleteFileFailed(val fileName: String) : VaultError
    data object MoveEntryFailed : VaultError
    data object AppendPagesFailed : VaultError
    data object DeletePageFailed : VaultError
    data object SaveNotesFailed : VaultError
    data class UpdateLabelFailed(val fileName: String) : VaultError
    data object DownloadPageFailed : VaultError
    data object OutboxUploadFailed : VaultError
    data object EditPageFailed : VaultError
    data object OpenEditorFailed : VaultError
}
