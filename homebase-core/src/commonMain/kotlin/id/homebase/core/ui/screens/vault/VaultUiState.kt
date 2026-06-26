package id.homebase.core.ui.screens.vault

import id.homebase.chat.conversationlist.AttachmentPendingFile
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
    /**
     * Payload keys currently downloading/decrypting for a share or save. Drives the
     * gallery's per-page spinner and re-entrancy guard so the share button isn't a dead,
     * tappable-forever control while the payload preps (#850).
     */
    val preparingShareKeys: Set<String> = emptySet(),
    /**
     * Staged attachments shown in the full-screen [id.homebase.chat.widget.MediaAttachmentEditor]
     * before they're committed to the vault. Lives in state (not a one-time event) so it
     * survives navigating out to the crop/draw screen and back. Null when the editor is closed.
     */
    val pendingEditor: VaultPendingEditor? = null,
)

/** Which editor screen a crop/draw request routes to. */
enum class VaultEditorTool { Crop, Draw }

/**
 * The staging buffer for the add-pictures editor. [appendTo] non-null appends the
 * confirmed attachments to that existing entry; otherwise a new entry is created in
 * [sectionId].
 */
data class VaultPendingEditor(
    val attachments: List<AttachmentPendingFile>,
    val sectionId: Uuid?,
    val appendTo: VaultEntry?,
)

sealed interface VaultOverlay {
    data class Gallery(val file: VaultEntry, val initialPage: Int = 0) : VaultOverlay
}

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
        /** Optional user-supplied entry name; falls back to the first file's name when blank/null. */
        val entryName: String? = null,
    ) : VaultUiAction

    data class AppendPages(
        val file: VaultEntry,
        val newFiles: List<PlatformFile>,
    ) : VaultUiAction

    // region Add-pictures editor

    /** Stage freshly-picked images in the editor (instead of adding them directly). */
    data class OpenAddEditor(
        val files: List<PlatformFile>,
        val sectionId: Uuid?,
        val appendTo: VaultEntry?,
    ) : VaultUiAction

    /** Add more files to the already-open editor (the in-editor add/camera buttons). */
    data class AddToEditor(val files: List<PlatformFile>) : VaultUiAction

    /** Remove a staged attachment from the open editor. */
    data class RemoveFromEditor(val attachmentId: Uuid) : VaultUiAction

    /** Route a staged image through the crop/draw editor; the result replaces it in place. */
    data class EditStagedImage(val attachmentId: Uuid, val tool: VaultEditorTool) : VaultUiAction

    /** Commit the staged attachments to the vault (add new entry or append). */
    data class ConfirmAddEditor(val entryName: String?) : VaultUiAction

    /** Close the editor, discarding the staged attachments. */
    data object DismissAddEditor : VaultUiAction

    /** Re-edit an already-stored image page; the edited bytes replace the payload in place. */
    data class EditExistingPage(
        val file: VaultEntry,
        val payloadKey: String,
        val tool: VaultEditorTool,
    ) : VaultUiAction

    // endregion

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
