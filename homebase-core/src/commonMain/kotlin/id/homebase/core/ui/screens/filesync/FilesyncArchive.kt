package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.drives.files.PayloadDamagedException
import id.homebase.api.file.zip.ZipStreamWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onEach
import kotlin.uuid.Uuid

/** One entry of an archive: where it goes in it, and for a file what to download. */
internal class FilesyncArchiveEntry(val path: List<String>, val node: FilesyncNode, val payload: FilesyncPayload?)

/** A file failed its hash check; the archive that held it is thrown away. */
internal class FilesyncArchiveDamaged(val name: String, cause: Throwable) : Exception(cause)

/**
 * Several nodes as one ZIP: the live nodes under them are listed first, a level of folders at a time, which downloads
 * nothing; then each file is downloaded, checked and written in as it streams. [list] gives the live nodes of some
 * folders; [stream] hands a payload's checked plaintext to its consumer and throws if it's damaged.
 */
internal class FilesyncArchive(
    private val list: suspend (folderIds: List<Uuid>) -> FilesyncNodes,
    private val stream: suspend (FilesyncPayload, consume: suspend (Flow<ByteArray>) -> Unit) -> Unit,
) {
    private class Pending(val node: FilesyncNode, val handle: FilesyncHandle?, val parent: List<String>)

    /** [nodes] and everything live below them, each folder before what's in it; deleted nodes are left out. */
    suspend fun plan(nodes: List<FilesyncNode>, handles: Map<Uuid, FilesyncHandle>): List<FilesyncArchiveEntry> {
        val entries = mutableListOf<FilesyncArchiveEntry>()
        val seen = mutableSetOf<Uuid>()
        var level = nodes.map { Pending(it, handles[it.nodeId], emptyList()) }
        while (level.isNotEmpty()) {
            val folders = mutableMapOf<Uuid, List<String>>()
            for (pending in level) {
                val node = pending.node
                if (node.removedMs != null || !seen.add(node.nodeId)) continue
                val path = pending.parent + node.name
                entries += FilesyncArchiveEntry(path, node, pending.handle?.payload)
                if (node.isFolder) folders[node.nodeId] = path
            }
            if (folders.isEmpty()) break
            val inside = list(folders.keys.toList())
            level = inside.nodes.values.sortedBy { it.nameKey }.mapNotNull { child ->
                folders[child.parentId]?.let { Pending(child, inside.handles[child.nodeId], it) }
            }
        }
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
    }
}
