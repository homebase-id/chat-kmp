package id.homebase.core.share

import co.touchlab.kermit.Logger
import id.homebase.api.file.safeDeleteRecursively
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

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
        val shareDirs = descriptor.fileNames.mapNotNull { it.substringBefore('/', "").ifEmpty { null } }.toSet()
        if (shareDirs.isEmpty()) {
            cacheStorage.clearSharedContent()
        } else {
            val root = cacheStorage.getSharedFilesDirectory()
            shareDirs.forEach { safeDeleteRecursively(root, it) }
        }
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
