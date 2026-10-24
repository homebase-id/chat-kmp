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
        if (!isActivated()) {
            deleteQuietly(localPath)
            return false
        }
        val id = try {
            upload(entryName, listOf(localPath to contentType), appScope)
        } catch (e: CancellationException) {
            deleteQuietly(localPath)
            throw e
        } catch (_: Exception) {
            null
        }
        if (id == null) deleteQuietly(localPath)
        return id != null
    }

    private fun deleteQuietly(path: String) {
        try { fileOperationsProvider.deleteTempFile(path) } catch (_: Exception) { }
    }
}
