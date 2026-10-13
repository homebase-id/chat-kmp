package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.PayloadDamagedException
import id.homebase.core.util.isWeb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** One download at a time for a screen: its progress goes to [onTransfer], the finished file out as an event. [handles] are the listed nodes'. */
internal class FilesyncTransfers(
    private val scope: CoroutineScope,
    private val repository: FilesyncRepository,
    private val onTransfer: ((FilesyncTransfer?) -> FilesyncTransfer?) -> Unit,
    private val send: suspend (FilesyncUiEvent) -> Unit,
) {
    private var job: Job? = null

    var handles: Map<Uuid, FilesyncHandle> = emptyMap()

    // The web can't hand a file to another app, so opening it means downloading it.
    fun open(node: FilesyncNode) = start(node, save = isWeb())

    fun save(node: FilesyncNode) = start(node, save = true)

    fun cancel() {
        job?.cancel()
    }

    private fun start(node: FilesyncNode, save: Boolean) {
        val payload = handles[node.nodeId]?.payload ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            onTransfer { FilesyncTransfer(node.nodeId) }
            try {
                val path = repository.download(node.name, payload, forOpen = !save) { progress ->
                    onTransfer { it?.copy(progress = progress) }
                }
                send(
                    when {
                        path == null -> FilesyncUiEvent.Error(FilesyncError.DownloadFailed)
                        save -> FilesyncUiEvent.SaveFile(path, node.name)
                        else -> FilesyncUiEvent.OpenFile(path)
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: PayloadDamagedException) {
                Logger.e(e, TAG) { "file ${node.nodeId} failed its hash check" }
                send(FilesyncUiEvent.Error(FilesyncError.Damaged))
            } catch (e: Exception) {
                Logger.e(e, TAG) { "download of ${node.nodeId} failed" }
                send(FilesyncUiEvent.Error(FilesyncError.DownloadFailed))
            } finally {
                onTransfer { null }
            }
        }
    }

    private companion object {
        const val TAG = "FilesyncTransfers"
    }
}
