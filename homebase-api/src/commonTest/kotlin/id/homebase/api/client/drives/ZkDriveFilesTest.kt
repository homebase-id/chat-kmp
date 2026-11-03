package id.homebase.api.client.drives

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.crypto.EncryptionType
import id.homebase.api.crypto.ZkLock
import id.homebase.api.crypto.ZkSeal
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.youauth.CallerContext
import id.homebase.api.youauth.DriveGrant
import id.homebase.api.youauth.DrivePermission
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
    fun aZeroKnowledgeDriveTheAppHasNoCopyForYetIsLocked() = runTest {
        ZkKeyStore.saveAppKey(appKey)
        val filesync = Uuid.random()
        val noCopy = grant(filesync, null).copy(isZeroKnowledge = true).let {
            it.copy(permissionedDrive = it.permissionedDrive.copy(permission = listOf(DrivePermission.Read)))
        }
        val depositOnly = grant(Uuid.random(), null).copy(isZeroKnowledge = true).let {
            it.copy(permissionedDrive = it.permissionedDrive.copy(permission = listOf(DrivePermission.Write)))
        }
        ZkKeyStore.openDriveKeys(SecurityContext(CallerContext(securityLevel = "app"), PermissionContext(listOf(PermissionGroup(driveGrants = listOf(noCopy, depositOnly))))))
        assertTrue(ZkKeyStore.isZeroKnowledge(filesync))
        assertEquals(setOf(filesync), ZkKeyStore.lockedDrives.value, "a write-only grant isn't locked")
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

    @Test
    fun aSealedFileFromTheServerOpensWithTheDrivesWriteOnlyKey() = runTest {
        val ecdh = CryptographyProvider.Default.get(ECDH)
        val writeOnly = ecdh.keyPairGenerator(EC.Curve.P384).generateKey()
        val email = Uuid.random()
        val withWriteOnlyKey = DriveGrant(
            permissionedDrive = PermissionedDrive(DriveReference(email.toString(), Uuid.random().toString()), emptyList()),
            zkAppKeyEncryptedStorageKey = Base64.encode(ZkLock.lock(driveKey, appKey)),
            zkStorageKeyEncryptedWriteOnlyPrivateKey = Base64.encode(
                ZkLock.lock(writeOnly.privateKey.encodeToByteArray(EC.PrivateKey.Format.DER.Generic), driveKey)
            ),
        )
        ZkKeyStore.saveAppKey(appKey)
        ZkKeyStore.openDriveKeys(SecurityContext(CallerContext(securityLevel = "app"), PermissionContext(listOf(PermissionGroup(driveGrants = listOf(withWriteOnlyKey))))))

        val fileKey = KeyHeader.newRandom16()
        val sealed = EncryptedKeyHeader(
            type = EncryptionType.Sealed,
            iv = fileKey.iv,
            encryptedAesKey = ZkSeal.seal(fileKey.combine().toByteArray(), writeOnly.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW.Uncompressed)),
        )
        assertEquals(fileKey, sealed.open(email, SecureByteArray(sharedSecret)))

        ZkKeyStore.clear()
        assertFailsWith<FileDecryptionException.Sealed> { sealed.open(email, SecureByteArray(sharedSecret)) }
    }

    // The golden vector odin-core ClientLockedKeyHeaderVectorTests and odin-js SecurityHelpers.test.ts check: change all three or none.
    private object Golden {
        val driveKey = Base64.decode("AAECAwQFBgcICQoLDA0ODw==")
        val iv = Base64.decode("EBESExQVFhcYGRobHB0eHw==")
        val aesKey = Base64.decode("ICEiIyQlJicoKSorLC0uLw==")
        val zkDriveEncryptedAesKey = Base64.decode("xqE7N4ePW4JvT4FiocjYeQeFPxlO+hclYcV072RPhmMJbAd9FxFTFUuXv4ql/85L")
        val writeOnlyPrivateKeyPkcs8 = Base64.decode(
            "MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDCZAel29rv0BgBwa7LGp4+tmSi5g4LlRzkW9/ZLQNgeVdrB44imUOGeaKmn1eiPZsShZANiAATKPHfd0ojszgjchR06XVN2p/iaosbJedgQGD86jcH39CM7EjWiXa6wVTW3cWGdDXwWQYx4NHDg8lIWULARJhvn02W1/eoTtDkhdpQrY6KmVbxpY/ehNZlDlhlh3MntF40="
        )
        val sealedEncryptedAesKey = Base64.decode(
            "ARRSmXQEgEhVDqJf8V4wR/vfRnjuBiMDCgpqQ0EvwP8Sbhg/htjK6DPN0tPu9kf4VKIvY0JpI7ekZehCr+RJr6buacGObBkW+4KXwPFn4aNzze/w+3mTcGuj+8oKsvICcbFc5rPogLwn6Oz/OQuWmSn3xtY70ljeN7b6eV3wkBqmS0sKbr1o/iBvA+hgSlg/4KiDGsTq2pxlrCRLPvr2Vxjk"
        )
    }

    @Test
    fun theGoldenZkDriveAndSealedKeyHeadersOpenToTheSameFileKey() = runTest {
        val fileKey = KeyHeader(Golden.iv, SecureByteArray(Golden.aesKey))
        assertContentEquals(Golden.iv + Golden.aesKey, ZkSeal.open(Golden.sealedEncryptedAesKey, Golden.writeOnlyPrivateKeyPkcs8))
        val zkDrive = EncryptedKeyHeader(type = EncryptionType.ZkDrive, iv = Golden.iv, encryptedAesKey = Golden.zkDriveEncryptedAesKey)
        assertEquals(fileKey, zkDrive.decryptAesToKeyHeader(SecureByteArray(Golden.driveKey)))

        val email = Uuid.random()
        val grant = DriveGrant(
            permissionedDrive = PermissionedDrive(DriveReference(email.toString(), Uuid.random().toString()), emptyList()),
            zkAppKeyEncryptedStorageKey = Base64.encode(ZkLock.lock(Golden.driveKey, appKey)),
            zkStorageKeyEncryptedWriteOnlyPrivateKey = Base64.encode(ZkLock.lock(Golden.writeOnlyPrivateKeyPkcs8, Golden.driveKey)),
        )
        ZkKeyStore.saveAppKey(appKey)
        ZkKeyStore.openDriveKeys(SecurityContext(CallerContext(securityLevel = "app"), PermissionContext(listOf(PermissionGroup(driveGrants = listOf(grant))))))
        val sealed = EncryptedKeyHeader(type = EncryptionType.Sealed, iv = Golden.iv, encryptedAesKey = Golden.sealedEncryptedAesKey)
        assertEquals(fileKey, sealed.open(email, SecureByteArray(sharedSecret)))
        assertEquals(fileKey, zkDrive.open(email, SecureByteArray(sharedSecret)))
    }

    private suspend fun unlockVault() {
        ZkKeyStore.saveAppKey(appKey)
        ZkKeyStore.openDriveKeys(context())
    }
}
