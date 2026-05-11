package id.homebase.core.ui.screens.vault

/**
 * Represents the upload status of a vault file.
 */
sealed interface VaultUploadStatus {
    data object Preparing : VaultUploadStatus
    data class Uploading(val progress: Float) : VaultUploadStatus
    data object Completed : VaultUploadStatus
    data class Failed(val error: String) : VaultUploadStatus
}
