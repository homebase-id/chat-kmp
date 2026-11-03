package id.homebase.chat.widget.preview

internal const val DOCX_XML_LIMIT_BYTES = 4L shl 20

internal expect val docxPreviewAvailable: Boolean

/** The bytes of the archive's word/document.xml, at most [DOCX_XML_LIMIT_BYTES] + 1 of them; throws when the file is not a docx. */
internal expect fun readDocxDocumentXml(path: String): ByteArray

private val PARAGRAPH_END = Regex("</w:p>")
private val RUN_PIECE = Regex("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>|<w:tab\\s*/>|<w:br\\s*/>|<w:cr\\s*/>")
private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")

private fun unescapeXml(text: String): String = if ('&' !in text) text else ENTITY.replace(text) { m ->
    when (val e = m.groupValues[1]) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        else -> {
            val code = if (e.startsWith("#x")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
            if (code != null && code in 0..0x10FFFF && code !in 0xD800..0xDFFF) charsFromCodePoint(code) else m.value
        }
    }
}

private fun charsFromCodePoint(code: Int): String =
    if (code < 0x10000) code.toChar().toString() else {
        val v = code - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

/** The text of a docx's document.xml, one entry per paragraph; formatting, tables' structure, images and fields are dropped. */
internal fun docxParagraphs(xml: String): List<String> =
    xml.split(PARAGRAPH_END).map { paragraph ->
        buildString {
            for (piece in RUN_PIECE.findAll(paragraph)) {
                val text = piece.groups[1]
                when {
                    text != null -> append(unescapeXml(text.value))
                    piece.value.startsWith("<w:tab") -> append('\t')
                    else -> append('\n')
                }
            }
        }
    }.dropLastWhile { it.isEmpty() }.flatMap { it.split('\n') }
