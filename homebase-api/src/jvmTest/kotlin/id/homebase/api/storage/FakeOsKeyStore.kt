package id.homebase.api.storage

import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKey

internal class FakeOsKeyStore(var forced: OsKeyRead? = null, var stored: String? = null) : OsKeyStore {
    var readCalls = 0
    var writeCalls = 0
    var deleteCalls = 0
    var failWrite: Throwable? = null
    var readbackOverride: String? = null
    var garbledReads = 0

    override fun read(): OsKeyRead {
        readCalls++
        if (garbledReads > 0) {
            garbledReads--
            return OsKeyRead.Found("garbage")
        }
        forced?.let { return it }
        return (readbackOverride ?: stored)?.let(OsKeyRead::Found) ?: OsKeyRead.Absent
    }

    override fun write(value: String) {
        writeCalls++
        failWrite?.let { throw it }
        stored = value
    }

    override fun delete() {
        deleteCalls++
        stored = null
    }
}

internal fun noStore() = OsKeyRead.NoStore(IllegalStateException("no store"))

/** Seals [values] and leaves the PKCS12 key file an older version wrote; returns that file's key. */
internal fun writeLegacyInstall(dir: File, values: Map<String, String>): SecretKey {
    val legacy = KeyStoreFileStorage(dir, FakeOsKeyStore(noStore()))
    values.forEach { (k, v) -> legacy.put(k, v) }
    return readFileKey(dir)
}

internal fun readFileKey(dir: File): SecretKey {
    val keyStore = KeyStore.getInstance("PKCS12")
    keyStoreFile(dir).inputStream().use { keyStore.load(it, "SecureStorageKeyStorePassword".toCharArray()) }
    return keyStore.getKey("SecureStorageKey", "SecureStorageKeyStorePassword".toCharArray()) as SecretKey
}

internal fun tempStorageDir(): File = java.nio.file.Files.createTempDirectory("secure-storage").toFile()
