package id.homebase.chat.widget.preview

internal actual val docxPreviewAvailable: Boolean = false

internal actual fun readDocxDocumentXml(path: String): ByteArray = error("docx preview is unavailable on web")
