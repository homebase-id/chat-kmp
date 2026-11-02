package id.homebase.api.storage

import java.io.File

internal class DpapiException(val code: Int, cause: Throwable? = null) : Exception("DPAPI error 0x${code.toString(16)}", cause)

/** The key, sealed with DPAPI (tied to the Windows login, unlike roaming Credential Manager entries), in a file beside the data. A blob that won't unprotect is unreachable, never absent: the cause can be transient (password reset, master key unavailable). */
internal class WindowsOsKeyStore(
    storageDir: File,
    fileName: String = "secure_storage.key.dpapi",
    private val protect: (ByteArray) -> ByteArray = DpapiCalls::protect,
    private val unprotect: (ByteArray) -> ByteArray = DpapiCalls::unprotect,
) : OsKeyStore {
    private val blobFile = File(storageDir, fileName)

    override fun read(): OsKeyRead = guardedOsRead {
        if (!blobFile.exists()) return@guardedOsRead OsKeyRead.Absent
        try {
            OsKeyRead.Found(String(unprotect(blobFile.readBytes()), Charsets.UTF_8))
        } catch (e: DpapiException) {
            OsKeyRead.Unreachable(e)
        }
    }

    override fun write(value: String) {
        blobFile.parentFile?.mkdirs()
        blobFile.writeBytes(protect(value.toByteArray(Charsets.UTF_8)))
    }

    override fun delete() {
        blobFile.delete()
    }
}

internal object DpapiCalls {
    fun protect(data: ByteArray): ByteArray = translate { com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(data) }

    fun unprotect(data: ByteArray): ByteArray = translate { com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(data) }

    private inline fun translate(block: () -> ByteArray): ByteArray =
        try {
            block()
        } catch (e: com.sun.jna.platform.win32.Win32Exception) {
            throw DpapiException(e.errorCode, e)
        }
}
