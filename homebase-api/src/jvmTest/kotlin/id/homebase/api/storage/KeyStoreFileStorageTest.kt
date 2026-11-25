package id.homebase.api.storage

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.security.KeyStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

private const val STORE_PASSWORD = "SecureStorageKeyStorePassword"
private const val ALIAS = "SecureStorageKey"

internal fun keyStoreFile(dir: File) = File(dir, "secure_storage.p12")
internal fun dataFile(dir: File) = File(dir, "secure_storage.properties")

// setReadable(false) does nothing on Windows, where a deny entry for this user does it.
internal fun File.setReadableOnAnyOs(readable: Boolean) {
    val acl = Files.getFileAttributeView(toPath(), AclFileAttributeView::class.java)
    if (acl == null) {
        check(setReadable(readable)) { "Couldn't change $this" }
        return
    }
    val user = FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    val deny = AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(user).setPermissions(AclEntryPermission.READ_DATA).build()
    acl.acl = if (readable) acl.acl - deny else listOf(deny) + acl.acl
}

/** Re-protects the storage key with a password the store will never use, as a corrupted entry would be. */
internal fun makeStorageKeyUnrecoverable(dir: File) {
    val file = keyStoreFile(dir)
    val keyStore = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, STORE_PASSWORD.toCharArray()) } }
    val key = keyStore.getKey(ALIAS, STORE_PASSWORD.toCharArray())
    keyStore.setKeyEntry(ALIAS, key, "not-the-store-password".toCharArray(), null)
    file.outputStream().use { keyStore.store(it, STORE_PASSWORD.toCharArray()) }
}

class KeyStoreFileStorageTest {
    private val dir: File = Files.createTempDirectory("secure-storage").toFile()
    private fun newStorage() = KeyStoreFileStorage(dir, FakeOsKeyStore(OsKeyRead.NoStore(IllegalStateException("no store"))))
    private var storage = newStorage()

    @AfterTest
    fun cleanup() {
        dir.walk().forEach { it.setReadableOnAnyOs(true) }
        dir.deleteRecursively()
    }

    @Test
    fun `a value sealed by an unrecoverable key is missing and the next put replaces the key`() {
        storage.put("k", "v")
        makeStorageKeyUnrecoverable(dir)
        storage = newStorage()

        assertEquals(SecureRead.Missing, storage.read("k"))
        storage.put("k", "v2")
        assertEquals(SecureRead.Found("v2"), storage.read("k"))
    }

    @Test
    fun `a lost keystore file with sealed values is unavailable and the read writes no new key`() {
        storage.put("k", "v")
        keyStoreFile(dir).delete()
        storage = newStorage()

        assertIs<SecureRead.Unavailable>(storage.read("k"))
        assertFalse(keyStoreFile(dir).exists())
    }

    @Test
    fun `an unreadable keystore file is unavailable, not missing`() {
        storage.put("k", "v")
        keyStoreFile(dir).setReadableOnAnyOs(false)
        storage = newStorage()

        assertIs<SecureRead.Unavailable>(storage.read("k"))
    }

    @Test
    fun `an unreadable data file is unavailable, not missing`() {
        storage.put("k", "v")
        dataFile(dir).setReadableOnAnyOs(false)

        assertIs<SecureRead.Unavailable>(storage.read("k"))
    }
}
