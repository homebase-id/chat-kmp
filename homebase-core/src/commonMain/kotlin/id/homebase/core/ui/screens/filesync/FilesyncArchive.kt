package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import id.homebase.api.client.drives.files.PayloadDamagedException
import id.homebase.api.file.zip.ZipStreamWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onEach
import kotlin.uuid.Uuid

/** One entry of an archive: where it goes in it, and for a file what to download. */
internal class FilesyncArchiveEntry(val path: List<String>, val node: FilesyncNode, val payload: FilesyncPayload?)

/** How far an archive has got; [totalBytes] is known once the folders are listed, null while they are. */
@Immutable
data class FilesyncArchiveProgress(val doneBytes: Long = 0, val totalBytes: Long? = null)

/** A file failed its hash check; the archive that held it is thrown away. */
internal class FilesyncArchiveDamaged(val name: String, cause: Throwable) : Exception(cause)

/**
 * Several nodes as one ZIP: the live nodes under them are listed first, which downloads nothing, then each file is
 * downloaded, checked and written in as it streams. [list] gives a folder's live nodes; [stream] hands a payload's
 * checked plaintext to its consumer and throws if it's damaged.
 */
internal class FilesyncArchive(
    private val list: suspend (folderId: Uuid) -> FilesyncNodes,
    private val stream: suspend (FilesyncPayload, consume: suspend (Flow<ByteArray>) -> Unit) -> Unit,
) {
    /** [nodes] and everything live below them, each folder before what's in it; deleted nodes are left out. */
    suspend fun plan(nodes: List<FilesyncNode>, handles: Map<Uuid, FilesyncHandle>): List<FilesyncArchiveEntry> {
        val entries = mutableListOf<FilesyncArchiveEntry>()
        val seen = mutableSetOf<Uuid>()
        suspend fun add(node: FilesyncNode, handle: FilesyncHandle?, parent: List<String>) {
            if (node.removedMs != null || !seen.add(node.nodeId)) return
            val path = parent + node.name
            entries += FilesyncArchiveEntry(path, node, handle?.payload)
            if (!node.isFolder) return
            val inside = list(node.nodeId)
            for (child in inside.nodes.values.sortedBy { it.nameKey }) add(child, inside.handles[child.nodeId], path)
        }
        for (node in nodes) add(node, handles[node.nodeId], emptyList())
        return entries
    }

    /** Streams the ZIP of [entries] to [out]; on any failure what [out] got must be thrown away. */
    suspend fun write(entries: List<FilesyncArchiveEntry>, out: suspend (ByteArray) -> Unit, onBytes: (Long) -> Unit) {
        val zip = ZipStreamWriter(out)
        var done = 0L
        val counted: (Flow<ByteArray>) -> Flow<ByteArray> = { data ->
            data.onEach {
                done += it.size
                onBytes(done)
            }
        }
        for (entry in entries) {
            val node = entry.node
            val modified = node.modifiedMs ?: node.updatedMs
            val payload = entry.payload
            when {
                node.isFolder -> zip.addFolder(entry.path, modified)
                payload == null -> zip.addFile(entry.path, modified, node.executable, 0, emptyFlow())
                else -> try {
                    stream(payload) { data -> zip.addFile(entry.path, modified, node.executable, node.sizeBytes ?: 0, counted(data)) }
                } catch (e: PayloadDamagedException) {
                    throw FilesyncArchiveDamaged(node.name, e)
                }
            }
        }
        zip.finish()
    }

    companion object {
        /** A lone folder's own name, else the folder they're in, else Filesync's. */
        fun name(nodes: List<FilesyncNode>, folderName: String?, fallback: String): String {
            val base = nodes.singleOrNull()?.takeIf { it.isFolder }?.name ?: folderName ?: fallback
            return base.replace('/', '_').replace('\\', '_') + ".zip"
        }

        /** The bytes to download, from what the nodes say they hold. */
        fun totalBytes(entries: List<FilesyncArchiveEntry>): Long = entries.sumOf { it.node.sizeBytes ?: 0 }
    }
}
