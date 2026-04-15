package id.homebase.feed.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import co.touchlab.kermit.Logger
import java.io.File

/**
 * Extracts shared content from an incoming ACTION_SEND or ACTION_SEND_MULTIPLE intent.
 * Copies shared URIs to the app's temp directory to avoid URI permission expiration.
 */
object SharedContentExtractor {

    private const val TAG = "SharedContentExtractor"

    fun extract(intent: Intent, contentResolver: ContentResolver, tempDir: File): SharedContent? {
        return when (intent.action) {
            Intent.ACTION_SEND -> extractSingle(intent, contentResolver, tempDir)
            Intent.ACTION_SEND_MULTIPLE -> extractMultiple(intent, contentResolver, tempDir)
            else -> null
        }
    }

    private fun extractSingle(intent: Intent, contentResolver: ContentResolver, tempDir: File): SharedContent? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val mimeType = intent.type ?: "*/*"

        if (text == null && uri == null) return null

        val files = if (uri != null) {
            listOfNotNull(copyUriToTemp(uri, mimeType, contentResolver, tempDir))
        } else {
            emptyList()
        }

        return SharedContent(
            text = text,
            files = files,
        )
    }

    private fun extractMultiple(intent: Intent, contentResolver: ContentResolver, tempDir: File): SharedContent? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)

        @Suppress("DEPRECATION")
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        val mimeType = intent.type ?: "*/*"

        if (text == null && uris.isEmpty()) return null

        val files = uris.mapNotNull { uri ->
            copyUriToTemp(uri, mimeType, contentResolver, tempDir)
        }

        return SharedContent(
            text = text,
            files = files,
        )
    }

    /**
     * Copy a shared URI's content to a temp file. Source URIs have ephemeral read permissions
     * that expire when the share activity finishes, so we must copy eagerly.
     */
    private fun copyUriToTemp(
        uri: Uri,
        fallbackMimeType: String,
        contentResolver: ContentResolver,
        tempDir: File,
    ): SharedFile? {
        return try {
            tempDir.mkdirs()

            val mimeType = contentResolver.getType(uri) ?: fallbackMimeType
            val extension = mimeTypeToExtension(mimeType)
            val fileName = "share_${System.currentTimeMillis()}_${(0..9999).random()}.$extension"
            val tempFile = File(tempDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            SharedFile(
                path = tempFile.absolutePath,
                mimeType = mimeType,
                displayName = queryDisplayName(uri, contentResolver) ?: fileName,
            )
        } catch (e: Exception) {
            Logger.e(tag = TAG) { "Failed to copy shared URI: ${e.message}" }
            null
        }
    }

    private fun queryDisplayName(uri: Uri, contentResolver: ContentResolver): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mimeTypeToExtension(mimeType: String): String {
        return when {
            mimeType.startsWith("image/jpeg") -> "jpg"
            mimeType.startsWith("image/png") -> "png"
            mimeType.startsWith("image/webp") -> "webp"
            mimeType.startsWith("image/gif") -> "gif"
            mimeType.startsWith("image/heic") || mimeType.startsWith("image/heif") -> "heic"
            mimeType.startsWith("video/mp4") -> "mp4"
            mimeType.startsWith("video/") -> "mp4"
            mimeType.startsWith("audio/") -> "m4a"
            mimeType.startsWith("application/pdf") -> "pdf"
            mimeType.startsWith("text/") -> "txt"
            else -> "bin"
        }
    }
}

data class SharedContent(
    val text: String? = null,
    val files: List<SharedFile> = emptyList(),
) {
    val hasFiles: Boolean get() = files.isNotEmpty()
    val hasText: Boolean get() = !text.isNullOrBlank()
    val isEmpty: Boolean get() = !hasFiles && !hasText
}

data class SharedFile(
    val path: String,
    val mimeType: String,
    val displayName: String,
)
