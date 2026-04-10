package id.homebase.core.util

/**
 * Detects file format from magic bytes in the file header.
 * Pure function — no I/O, no threading concerns.
 * Pass only the first 16 bytes of the file.
 */
object FileFormatDetector {

    /**
     * Returns a MIME type if recognized, or null if unknown.
     * [header] should be the first 12–16 bytes of the file.
     */
    fun detectFromHeader(header: ByteArray): String? {
        if (header.size < 2) return null

        val b = header
        return when {
            // PDF: %PDF
            b.size >= 4 && b[0] == 0x25.toByte() && b[1] == 0x50.toByte() &&
                    b[2] == 0x44.toByte() && b[3] == 0x46.toByte() -> "application/pdf"

            // PNG: 89 50 4E 47
            b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
                    b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> "image/png"

            // JPEG: FF D8 FF
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() &&
                    b[2] == 0xFF.toByte() -> "image/jpeg"

            // GIF: GIF8
            b.size >= 4 && b[0] == 0x47.toByte() && b[1] == 0x49.toByte() &&
                    b[2] == 0x46.toByte() && b[3] == 0x38.toByte() -> "image/gif"

            // RIFF container: WEBP, AVI, WAV
            b.size >= 12 && b[0] == 0x52.toByte() && b[1] == 0x49.toByte() &&
                    b[2] == 0x46.toByte() && b[3] == 0x46.toByte() -> detectRiff(b)

            // BMP: BM
            b[0] == 0x42.toByte() && b[1] == 0x4D.toByte() -> "image/bmp"

            // ZIP (also docx/xlsx/pptx — extension distinguishes): PK\x03\x04
            b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() &&
                    b[2] == 0x03.toByte() && b[3] == 0x04.toByte() -> "application/zip"

            // GZIP: 1F 8B
            b[0] == 0x1F.toByte() && b[1] == 0x8B.toByte() -> "application/gzip"

            // RAR: Rar!\x1A\x07
            b.size >= 6 && b[0] == 0x52.toByte() && b[1] == 0x61.toByte() &&
                    b[2] == 0x72.toByte() && b[3] == 0x21.toByte() &&
                    b[4] == 0x1A.toByte() && b[5] == 0x07.toByte() -> "application/x-rar-compressed"

            // 7z: 37 7A BC AF 27 1C
            b.size >= 6 && b[0] == 0x37.toByte() && b[1] == 0x7A.toByte() &&
                    b[2] == 0xBC.toByte() && b[3] == 0xAF.toByte() &&
                    b[4] == 0x27.toByte() && b[5] == 0x1C.toByte() -> "application/x-7z-compressed"

            // ICO: 00 00 01 00
            b.size >= 4 && b[0] == 0x00.toByte() && b[1] == 0x00.toByte() &&
                    b[2] == 0x01.toByte() && b[3] == 0x00.toByte() -> "image/x-icon"

            // ISOBMFF (MP4, HEIC, MOV): ftyp at offset 4
            b.size >= 12 && b[4] == 0x66.toByte() && b[5] == 0x74.toByte() &&
                    b[6] == 0x79.toByte() && b[7] == 0x70.toByte() -> detectIsobmff(b)

            // MP3: ID3 tag
            b.size >= 3 && b[0] == 0x49.toByte() && b[1] == 0x44.toByte() &&
                    b[2] == 0x33.toByte() -> "audio/mpeg"

            // MP3: sync word FF FB / FF F3 / FF F2
            b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"

            // OGG: OggS
            b.size >= 4 && b[0] == 0x4F.toByte() && b[1] == 0x67.toByte() &&
                    b[2] == 0x67.toByte() && b[3] == 0x53.toByte() -> "audio/ogg"

            // FLAC: fLaC
            b.size >= 4 && b[0] == 0x66.toByte() && b[1] == 0x4C.toByte() &&
                    b[2] == 0x61.toByte() && b[3] == 0x43.toByte() -> "audio/flac"

            // Matroska/WebM: 1A 45 DF A3
            b.size >= 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() &&
                    b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> "video/webm"

            // WASM: \0asm
            b.size >= 4 && b[0] == 0x00.toByte() && b[1] == 0x61.toByte() &&
                    b[2] == 0x73.toByte() && b[3] == 0x6D.toByte() -> "application/wasm"

            // Text: check if all bytes are printable ASCII or common whitespace
            isLikelyText(b) -> "text/plain"

            else -> null
        }
    }

    private fun detectRiff(b: ByteArray): String {
        // Bytes 8-11 contain the RIFF type
        val type = try {
            b.sliceArray(8..11).decodeToString()
        } catch (_: Exception) {
            return "application/octet-stream"
        }
        return when (type) {
            "WEBP" -> "image/webp"
            "AVI " -> "video/x-msvideo"
            "WAVE" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    private fun detectIsobmff(b: ByteArray): String {
        // Brand at offset 8 (4 chars)
        val brand = try {
            b.sliceArray(8..11).decodeToString()
        } catch (_: Exception) {
            return "video/mp4"
        }
        return when {
            brand in listOf("heic", "heix", "hevc", "hevx", "mif1", "msf1") -> "image/heic"
            brand == "qt  " -> "video/quicktime"
            else -> "video/mp4"
        }
    }

    private fun isLikelyText(b: ByteArray): Boolean {
        // Check if all bytes look like printable ASCII or common whitespace
        for (byte in b) {
            val v = byte.toInt() and 0xFF
            val isTextChar = v in 0x20..0x7E || // printable ASCII
                    v == 0x09 ||                 // tab
                    v == 0x0A ||                 // newline
                    v == 0x0D                    // carriage return
            if (!isTextChar) return false
        }
        return true
    }
}
