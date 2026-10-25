package id.homebase.core.ui.screens.vault

import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.config.vaultLabeledDrive
import id.homebase.core.sync.OptionalDriveActivation
import id.homebase.core.vault.VaultFileSaver
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class VaultUploaderFileSaver internal constructor(
    private val isActivated: () -> Boolean,
    private val upload: suspend (entryName: String, files: List<Pair<String, String>>, scope: CoroutineScope) -> Uuid?,
    private val fileOperationsProvider: FileOperationsProvider,
    private val appScope: CoroutineScope,
) : VaultFileSaver {

    constructor(
        uploader: VaultUploaderService,
        activation: OptionalDriveActivation,
        fileOperationsProvider: FileOperationsProvider,
        appScope: CoroutineScope,
    ) : this(
        isActivated = { activation.isActivated(vaultLabeledDrive) },
        upload = { name, files, scope -> uploader.uploadFile(entryName = name, files = files, scope = scope) },
        fileOperationsProvider = fileOperationsProvider,
        appScope = appScope,
    )

    override suspend fun save(localPath: String, contentType: String, entryName: String): Boolean {
        try {
            if (!isActivated()) return false
            return try {
                upload(entryName, listOf(localPath to contentType), appScope) != null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        } finally {
            // The upload encrypts into its own file before returning, so the decrypted copy is spent either way.
            deleteQuietly(localPath)
        }
    }

    private fun deleteQuietly(path: String) {
        try { fileOperationsProvider.deleteTempFile(path) } catch (_: Exception) { }
    }
}
