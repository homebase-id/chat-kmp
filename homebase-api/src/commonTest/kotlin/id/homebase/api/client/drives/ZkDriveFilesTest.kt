package id.homebase.api.client.drives

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.crypto.EncryptionType
import id.homebase.api.crypto.ZkLock
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.youauth.CallerContext
import id.homebase.api.youauth.DriveGrant
import id.homebase.api.youauth.DriveReference
import id.homebase.api.youauth.PermissionContext
import id.homebase.api.youauth.PermissionGroup
import id.homebase.api.youauth.PermissionedDrive
import id.homebase.api.youauth.SecurityContext
import id.homebase.api.youauth.ZkKeyStore
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ZkDriveFilesTest {

    private class MemoryStorage : ZkKeyStore.Storage {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }

    private val sharedSecret = ByteArrayUtil.getRndByteArray(16)
    private val appKey = ByteArrayUtil.getRndByteArray(16)
    private val driveKey = ByteArrayUtil.getRndByteArray(16)
    private val vault = Uuid.random()
    private val chat = Uuid.random()

    @BeforeTest
    fun inMemory() {
        ZkKeyStore.storage = MemoryStorage()
    }

    @AfterTest
    fun reset() = ZkKeyStore.clear()

    private fun grant(alias: Uuid, locked: ByteArray?) = DriveGrant(
        permissionedDrive = PermissionedDrive(DriveReference(alias.toString(), Uuid.random().toString()), emptyList()),
        zkAppKeyEncryptedStorageKey = locked?.let(Base64::encode),
    )

    private suspend fun context() = SecurityContext(
        CallerContext(securityLevel = "app"),
        PermissionContext(listOf(PermissionGroup(driveGrants = listOf(grant(vault, ZkLock.lock(driveKey, appKey)), grant(chat, null))))),
    )

    @Test
    fun theContextOpensEachZeroKnowledgeDriveKeyWithTheAppKey() = runTest {
        ZkKeyStore.saveAppKey(appKey)
        ZkKeyStore.openDriveKeys(context())
        assertContentEquals(driveKey, ZkKeyStore.driveKey(vault))
        assertEquals(false, ZkKeyStore.isZeroKnowledge(chat))
        assertEquals(emptySet(), ZkKeyStore.lockedDrives.value)
    }

    @Test
    fun withoutTheAppKeyTheDriveIsZeroKnowledgeButLocked() = runTest {
        ZkKeyStore.openDriveKeys(context())
        assertTrue(ZkKeyStore.isZeroKnowledge(vault))
        assertNull(ZkKeyStore.driveKey(vault))
        assertEquals(setOf(vault), ZkKeyStore.lockedDrives.value)

        ZkKeyStore.saveAppKey(ByteArrayUtil.getRndByteArray(16))
        ZkKeyStore.openDriveKeys(context())
        assertEquals(setOf(vault), ZkKeyStore.lockedDrives.value, "another app key doesn't open it")
    }

    @Test
    fun aKeyHeaderOnAZeroKnowledgeDriveIsLockedWithItsKeyUnderTheFilesIv() = runTest {
        unlockVault()
        val fileKey = KeyHeader.newRandom16()

        val locked = lockKeyHeader(fileKey, vault, sharedSecret, ByteArrayUtil.getRndByteArray(16))
        assertEquals(EncryptionType.ZkDrive, locked.type)
        assertContentEquals(fileKey.iv, locked.iv)
        assertEquals(fileKey, locked.decryptAesToKeyHeader(SecureByteArray(driveKey)))
        assertEquals(fileKey, locked.open(vault, SecureByteArray(sharedSecret)))
        assertTrue(""""type":"zkDrive"""" in OdinSystemSerializer.serialize(locked))

        val elsewhere = lockKeyHeader(fileKey, chat, sharedSecret, ByteArrayUtil.getRndByteArray(16))
        assertEquals(EncryptionType.Aes, elsewhere.type)
        assertEquals(fileKey, elsewhere.open(chat, SecureByteArray(sharedSecret)))
    }

    @Test
    fun aZeroKnowledgeDriveThisDeviceCantOpenRefusesTheSharedSecret() = runTest {
        ZkKeyStore.openDriveKeys(context())
        assertFailsWith<FileDecryptionException.NoDriveKey> {
            lockKeyHeader(KeyHeader.newRandom16(), vault, sharedSecret, ByteArrayUtil.getRndByteArray(16))
        }
    }

    @Test
    fun aZkDriveFileFromTheServerOpensWithTheDriveKey() = runTest {
        unlockVault()
        val fileKey = KeyHeader.newRandom16()
        val keyHeader = lockKeyHeader(fileKey, vault, sharedSecret, ByteArrayUtil.getRndByteArray(16))
        val content = Base64.encode(fileKey.encryptDataAes("""{"name":"passport"}""".encodeToByteArray()))
        val json = """
            {
              "fileId": "${Uuid.random()}",
              "driveId": "$vault",
              "fileState": "active",
              "fileSystemType": "standard",
              "sharedSecretEncryptedKeyHeader": { "encryptionVersion": 1, "type": "zkDrive", "iv": "${Base64.encode(keyHeader.iv)}", "encryptedAesKey": "${Base64.encode(keyHeader.encryptedAesKey)}" },
              "fileMetadata": { "isEncrypted": true, "versionTag": "${Uuid.random()}", "appData": { "uniqueId": "${Uuid.random()}", "fileType": 1, "content": "$content" } },
              "serverMetadata": {}
            }
        """

        val file = OdinSystemSerializer.deserialize<ServerFile>(json).asHomebaseFile(SecureByteArray(sharedSecret))
        assertEquals(fileKey, file.keyHeader)
        assertEquals("""{"name":"passport"}""", file.fileMetadata.appData.content)
    }

    private suspend fun unlockVault() {
        ZkKeyStore.saveAppKey(appKey)
        ZkKeyStore.openDriveKeys(context())
    }
}
