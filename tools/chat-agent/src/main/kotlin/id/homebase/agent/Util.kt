package id.homebase.agent

import id.homebase.api.util.truncateToCodePoints
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

const val NAME_CODEPOINTS = 60
private const val DISPLAY_NAME_CODEPOINTS = 100
private const val OCTET_STREAM = "application/octet-stream"

fun atomicWrite(file: File, text: String) {
    val tmp = File.createTempFile(file.name, ".tmp", file.absoluteFile.parentFile)
    tmp.writeText(text)
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

fun tempDir(kind: String): File = Files.createTempDirectory("chat-agent-$kind").toFile()

private val CONTROL_OR_SPACE = Regex("[\\p{Cntrl}\\s]+")

fun String.oneLine(maxCodePoints: Int) = replace(CONTROL_OR_SPACE, " ").trim().truncateToCodePoints(maxCodePoints)

private val FILESYSTEM_UNSAFE = Regex("[^A-Za-z0-9._-]")
private val DOT_RUN = Regex("\\.{2,}")
private val DISPLAY_UNSAFE = Regex("[\\p{Cntrl}/\\\\]+")

// display keeps unicode: it is the name recipients see, not a path we open.
fun safeName(name: String, display: Boolean = false): String =
    if (display) {
        name.replace(DISPLAY_UNSAFE, "_").truncateToCodePoints(DISPLAY_NAME_CODEPOINTS)
    } else {
        name.replace(FILESYSTEM_UNSAFE, "_").replace(DOT_RUN, ".").trim('.', '_').ifEmpty { "file" }.truncateToCodePoints(NAME_CODEPOINTS)
    }

private val EXTENSION_TYPES = mapOf(
    "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
    "pdf" to "application/pdf", "json" to "application/json", "csv" to "text/csv",
    "txt" to "text/plain", "md" to "text/plain", "log" to "text/plain", "zip" to "application/zip",
)

fun contentTypeFor(name: String, declared: String? = null): String {
    if (!declared.isNullOrBlank() && declared != OCTET_STREAM) return declared
    return EXTENSION_TYPES[name.substringAfterLast('.', "").lowercase()] ?: declared ?: OCTET_STREAM
}

fun extensionFor(contentType: String): String =
    EXTENSION_TYPES.entries.firstOrNull { it.value == contentType }?.key
        ?: contentType.substringAfter('/', "").substringBefore(';').lowercase().takeIf { Regex("[a-z0-9]{1,8}").matches(it) } ?: "bin"
