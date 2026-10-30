package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.PayloadDamagedException
import id.homebase.api.client.drives.files.wholePercentFractions
import id.homebase.core.ui.screens.webdrop.model.PickedDropFile
import id.homebase.core.util.StreamedSave
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import id.homebase.core.util.isWeb
import id.homebase.core.util.openBrowserSave
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

private enum class Purpose { Open, Save, OtherApp, WebDrop, Chat }

/** What a transfer downloads: one file as itself, or several nodes as one ZIP called [name]. */
private sealed interface Source {
    val name: String

    class One(val node: FilesyncNode, val payload: FilesyncPayload) : Source {
        override val name get() = node.name
    }

    class Zip(val nodes: List<FilesyncNode>, override val name: String) : Source
}

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
    private val archive = FilesyncArchive(repository::listFolders) { payload, consume -> repository.streamPayload(payload, consume = consume) }

    var handles: Map<Uuid, FilesyncHandle> = emptyMap()

    // The web can't hand a file to another app, so opening it means downloading it.
    fun open(node: FilesyncNode) = one(node, if (isWeb()) Purpose.Save else Purpose.Open)

    fun save(node: FilesyncNode) = one(node, Purpose.Save)

    fun saveZip(nodes: List<FilesyncNode>, name: String) = start(Source.Zip(nodes, name), Purpose.Save)

    /** [nodes] to [target]: one file as itself, anything more, or a folder, as one ZIP called [zipName]. */
    fun send(nodes: List<FilesyncNode>, zipName: String, target: FilesyncShareTarget) {
        val purpose = when (target) {
            FilesyncShareTarget.Chat -> Purpose.Chat
            FilesyncShareTarget.WebDrop -> Purpose.WebDrop
            FilesyncShareTarget.OtherApp -> Purpose.OtherApp
        }
        val file = nodes.singleOrNull()?.takeIf { !it.isFolder }
        if (file != null) one(file, purpose) else start(Source.Zip(nodes, zipName), purpose)
    }

    fun cancel() {
        job?.cancel()
    }

    private fun one(node: FilesyncNode, purpose: Purpose) {
        val payload = handles[node.nodeId]?.payload ?: return
        start(Source.One(node, payload), purpose)
    }

    private fun start(source: Source, purpose: Purpose) {
        // Not isActive: a cancelled transfer is still putting things away.
        if (job?.isCompleted == false) return
        job = scope.launch {
            onTransfer { FilesyncTransfer((source as? Source.One)?.node?.nodeId) }
            val progress: (Float) -> Unit = { fraction -> onTransfer { it?.copy(progress = fraction) } }
            var browser: StreamedSave? = null
            try {
                // A browser shows its save picker only close to the click, so it's asked before anything is listed.
                if (purpose == Purpose.Save && isWeb()) {
                    browser = openBrowserSave(source.name, detectContentTypeFromExtensionOrHint(source.name)) ?: return@launch
                }
                val write: suspend (out: suspend (ByteArray) -> Unit) -> Unit = when (source) {
                    is Source.One -> { out -> repository.streamPayload(source.payload, progress) { data -> data.collect { out(it) } } }
                    is Source.Zip -> {
                        val entries = archive.plan(source.nodes, handles)
                        val total = entries.sumOf { it.node.sizeBytes ?: 0 }
                        onTransfer { it?.copy(totalBytes = total) }
                        val fractions = wholePercentFractions(progress)
                        ({ out -> archive.write(entries, out) { done -> if (total > 0) fractions(done.toFloat() / total) } })
                    }
                }
                if (browser != null) {
                    write(browser::write)
                    browser.close()
                    return@launch
                }
                // share_outbound is swept whenever the app comes back to the front, which a drop's composer outlives.
                val path = repository.writeToCache(source.name, toShareOutbound = purpose == Purpose.Open || purpose == Purpose.OtherApp, write)
                when (purpose) {
                    Purpose.Open -> send(FilesyncUiEvent.OpenFile(path))
                    Purpose.Save -> send(FilesyncUiEvent.SaveFile(path, source.name))
                    Purpose.OtherApp -> send(FilesyncUiEvent.ShareFile(path))
                    Purpose.Chat -> send(FilesyncUiEvent.SendToChat(path, source.name))
                    Purpose.WebDrop -> onWebDrop(PickedDropFile(path, source.name, detectContentTypeFromExtensionOrHint(source.name), repository.sizeOf(path)))
                }
            } catch (e: CancellationException) {
                browser?.let { withContext(NonCancellable) { runCatching { it.abort() } } }
                throw e
            } catch (e: Throwable) {
                // Not Exception: on the web a rejected promise surfaces as a JsException, which is only a Throwable.
                if (e is Error) throw e
                browser?.let { runCatching { it.abort() } }
                Logger.e(e, TAG) { "download of ${source.name} failed" }
                send(
                    when (e) {
                        is FilesyncArchiveDamaged -> FilesyncUiEvent.ArchiveDamaged(e.name)
                        is PayloadDamagedException -> FilesyncUiEvent.Error(FilesyncError.Damaged)
                        else -> FilesyncUiEvent.Error(FilesyncError.DownloadFailed)
                    }
                )
            } finally {
                onTransfer { null }
            }
        }
    }

    private companion object {
        const val TAG = "FilesyncTransfers"
    }
}
