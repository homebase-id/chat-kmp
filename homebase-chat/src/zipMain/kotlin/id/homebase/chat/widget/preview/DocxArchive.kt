package id.homebase.chat.widget.preview

import id.homebase.api.file.systemFileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

internal actual val docxPreviewAvailable: Boolean = true

internal actual fun readDocxDocumentXml(path: String): ByteArray {
    val zip = systemFileSystem.openZip(path.toPath())
    try {
        zip.source("/word/document.xml".toPath()).buffer().use { source ->
            source.request(DOCX_XML_LIMIT_BYTES + 1)
            return source.readByteArray(minOf(DOCX_XML_LIMIT_BYTES + 1, source.buffer.size))
        }
    } finally {
        zip.close()
    }
}
