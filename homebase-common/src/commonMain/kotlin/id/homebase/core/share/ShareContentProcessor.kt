package id.homebase.core.share

import co.touchlab.kermit.Logger
import id.homebase.api.file.safeDeleteRecursively
import id.homebase.api.file.systemFileSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

/**
 * Reads a [SharedContentDescriptor] from cache storage and provides it for sending.
 * Used by the main app when receiving a handoff from the iOS share extension,
 * and by the Android share activity to serialize/deserialize shared content.
 */
class ShareContentProcessor(
    private val cacheStorage: ShareCacheStorage,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val claimLock = Mutex()
    private val inFlight = MutableStateFlow<Set<String>>(emptySet())

    private fun shareDirsOf(descriptor: SharedContentDescriptor): Set<String> =
        descriptor.fileNames.mapNotNull { it.substringBefore('/', "").ifEmpty { null } }.toSet()

    /**
     * Read the pending descriptor and remove it in one step, so a second trigger for the
     * same share finds nothing. A descriptor [accept] refuses is left in place. The staged
     * files stay until [cleanup].
     */
    suspend fun claimPendingContent(
        accept: (SharedContentDescriptor) -> Boolean = { true },
    ): SharedContentDescriptor? = claimLock.withLock {
        val descriptor = readPendingContent() ?: return@withLock null
        if (!accept(descriptor)) return@withLock null
        inFlight.update { it + shareDirsOf(descriptor) }
        cacheStorage.clearSharedContentDescriptor()
        descriptor
    }

    /**
     * Read the pending shared content descriptor.
     * Returns null if no shared content is waiting.
     */
    fun readPendingContent(): SharedContentDescriptor? {
        return try {
            val raw = cacheStorage.readSharedContent() ?: return null
            json.decodeFromString<SharedContentDescriptor>(raw)
        } catch (e: Exception) {
            Logger.e(tag = "ShareContentProcessor") { "Failed to read shared content: ${e.message}" }
            null
        }
    }

    /**
     * Resolve a shared file name to its full path in the shared files directory.
     */
    fun resolveFilePath(fileName: String): String {
        return "${cacheStorage.getSharedFilesDirectory()}/$fileName"
    }

    /**
     * Delete the files of a claimed share. Names of the form `<shareId>/...` live in their
     * own directory, so only that directory goes; flat names (iOS extension) clear the lot.
     */
    fun cleanup(descriptor: SharedContentDescriptor) {
        if (descriptor.fileNames.isEmpty()) return
        val shareDirs = shareDirsOf(descriptor)
        inFlight.update { it - shareDirs }
        if (shareDirs.isEmpty()) {
            cacheStorage.clearSharedContent()
        } else {
            val root = cacheStorage.getSharedFilesDirectory()
            shareDirs.forEach { safeDeleteRecursively(root, it) }
        }
    }

    fun discardShare(shareId: String) {
        safeDeleteRecursively(cacheStorage.getSharedFilesDirectory(), shareId)
    }

    /**
     * Delete per-share directories nothing owns: left by a process that died mid-send, a
     * descriptor overwritten by a newer share, or a failed staging. Call before staging a
     * new share, whose predecessor's pending descriptor is about to be replaced. Claimed
     * in-flight shares and, when [keepPending], the pending descriptor's directory survive.
     */
    fun sweepOrphanedShares(keepPending: Boolean = false) {
        val keep = inFlight.value + if (keepPending) readPendingContent()?.let(::shareDirsOf).orEmpty() else emptySet()
        val root = cacheStorage.getSharedFilesDirectory()
        val dirs = try {
            systemFileSystem.listOrNull(root.toPath()).orEmpty()
                .filter { systemFileSystem.metadataOrNull(it)?.isDirectory == true }
        } catch (e: Exception) {
            Logger.e(tag = "ShareContentProcessor") { "Failed to list shared files: ${e.message}" }
            return
        }
        dirs.map { it.name }.filter { it !in keep }.forEach { safeDeleteRecursively(root, it) }
    }

    /**
     * Write a shared content descriptor (used by Android share activity or iOS extension).
     */
    fun writeContent(descriptor: SharedContentDescriptor) {
        try {
            val encoded = json.encodeToString(SharedContentDescriptor.serializer(), descriptor)
            cacheStorage.writeSharedContent(encoded)
        } catch (e: Exception) {
            Logger.e(tag = "ShareContentProcessor") { "Failed to write shared content: ${e.message}" }
        }
    }
}
