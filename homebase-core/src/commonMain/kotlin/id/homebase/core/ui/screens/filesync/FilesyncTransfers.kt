package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.PayloadDamagedException
import id.homebase.core.ui.screens.webdrop.model.PickedDropFile
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import id.homebase.core.util.isWeb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private enum class Purpose { Open, Save, Share, WebDrop }

/**
 * One download at a time for a screen: its progress goes to [onTransfer], the finished file out as an event,
 * or to [onWebDrop] when it's for a drop. [handles] are the listed nodes'.
 */
internal class FilesyncTransfers(
    private val scope: CoroutineScope,
    private val repository: FilesyncRepository,
    private val onTransfer: ((FilesyncTransfer?) -> FilesyncTransfer?) -> Unit,
    private val send: suspend (FilesyncUiEvent) -> Unit,
    private val onWebDrop: suspend (PickedDropFile) -> Unit = {},
) {
    private var job: Job? = null

    var handles: Map<Uuid, FilesyncHandle> = emptyMap()

    // The web can't hand a file to another app, so opening it means downloading it.
    fun open(node: FilesyncNode) = start(node, if (isWeb()) Purpose.Save else Purpose.Open)

    fun save(node: FilesyncNode) = start(node, Purpose.Save)

    fun share(node: FilesyncNode) = start(node, Purpose.Share)

    fun webDrop(node: FilesyncNode) = start(node, Purpose.WebDrop)

    fun cancel() {
        job?.cancel()
    }

    private fun start(node: FilesyncNode, purpose: Purpose) {
        val payload = handles[node.nodeId]?.payload ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            onTransfer { FilesyncTransfer(node.nodeId) }
            try {
                // share_outbound is swept whenever the app comes back to the front, which a drop's composer outlives.
                val path = repository.download(node.name, payload, toShareOutbound = purpose == Purpose.Open || purpose == Purpose.Share) { progress ->
                    onTransfer { it?.copy(progress = progress) }
                }
                when {
                    path == null -> send(FilesyncUiEvent.Error(FilesyncError.DownloadFailed))
                    purpose == Purpose.Open -> send(FilesyncUiEvent.OpenFile(path))
                    purpose == Purpose.Save -> send(FilesyncUiEvent.SaveFile(path, node.name))
                    purpose == Purpose.Share -> send(FilesyncUiEvent.ShareFile(path))
                    else -> onWebDrop(
                        PickedDropFile(path, node.name, detectContentTypeFromExtensionOrHint(node.name), node.sizeBytes ?: 0)
                    )
                }
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
