package id.homebase.chat.conversationlist

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.write

actual fun newRecordingFile(fileName: String): PlatformFile =
    PlatformFile(FileKit.filesDir, fileName)

actual fun newWaveformCacheFile(fileName: String): PlatformFile =
    PlatformFile(FileKit.cacheDir, fileName)

actual suspend fun PlatformFile.writeBytesCompat(bytes: ByteArray) = write(bytes)

actual suspend fun PlatformFile.deleteCompat(mustExist: Boolean) = delete(mustExist = mustExist)
