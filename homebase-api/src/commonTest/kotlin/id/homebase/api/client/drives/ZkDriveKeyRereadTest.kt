package id.homebase.api.client.drives

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.NetworkException
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.ServerException
import id.homebase.api.client.isTransientNetworkFailure
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.crypto.EncryptionType
import id.homebase.api.crypto.ZkLock
import id.homebase.api.youauth.CallerContext
import id.homebase.api.youauth.DriveGrant
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DriveReference
import id.homebase.api.youauth.PermissionContext
import id.homebase.api.youauth.PermissionGroup
import id.homebase.api.youauth.PermissionedDrive
import id.homebase.api.youauth.SecurityContext
import id.homebase.api.youauth.ZkKeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.io.IOException
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

// A drive made again gets a new key; a device that locks a new file with the key it saved before writes a file nobody
// with the current key can open, and the server can't tell.
class ZkDriveKeyRereadTest {

    private val sharedSecret = ByteArrayUtil.getRndByteArray(16)
    private val appKey = ByteArrayUtil.getRndByteArray(16)
    private val oldKey = ByteArrayUtil.getRndByteArray(16)
    private val newKey = ByteArrayUtil.getRndByteArray(16)
    private val photos = Uuid.random()
    private val clock = TestTimeSource()

    // What the server's grant says now; null when it can't be reached.
    private var server: SecurityContext? = null
    private var reads = 0
    private val resynced = mutableListOf<Uuid>()

    private fun unreachable() = NetworkException(IOException("unreachable"))

    @BeforeTest
    fun setUp() {
        ZkKeyStore.storage = MemoryZkStorage()
        ZkKeyStore.timeSource = clock
        ZkKeyStore.useServer(fetchGrant = { reads++; server ?: throw unreachable() }, resync = { resynced += it })
    }

    @AfterTest
    fun reset() {
        ZkKeyStore.clear()
        SealedKeyHeaders.dropped = null
        ZkKeyStore.timeSource = TimeSource.Monotonic
        ZkKeyStore.useServer(fetchGrant = { throw unreachable() }, resync = {})
    }

    private suspend fun grant(key: ByteArray?, zeroKnowledge: Boolean = true) = SecurityContext(
        CallerContext(securityLevel = "app"),
        PermissionContext(listOf(PermissionGroup(driveGrants = listOf(DriveGrant(
            permissionedDrive = PermissionedDrive(DriveReference(photos.toString(), Uuid.random().toString()), listOf(DrivePermission.Read, DrivePermission.Write)),
            isZeroKnowledge = zeroKnowledge,
            zkAppKeyEncryptedStorageKey = key?.let { Base64.encode(ZkLock.lock(it, appKey)) },
        ))))),
    )

    private suspend fun savedFromAnEarlierRun(key: ByteArray) {
        val saved = MemoryZkStorage()
        ZkKeyStore.storage = saved
        ZkKeyStore.saveAppKey(appKey)
        ZkKeyStore.openDriveKeys(grant(key))
        ZkKeyStore.storage = MemoryZkStorage(saved.values.toMutableMap())
        resynced.clear()
    }

    private suspend fun lockNewFile(fileKey: KeyHeader = KeyHeader.newRandom16()) =
        fileKey to lockKeyHeader(fileKey, photos, sharedSecret, ByteArrayUtil.getRndByteArray(16))

    private suspend fun opensWith(locked: Pair<KeyHeader, EncryptedKeyHeader>, key: ByteArray): Boolean = runCatching {
        locked.second.decryptAesToKeyHeader(SecureByteArray(key)) == locked.first
    }.getOrDefault(false)

    @Test
    fun aKeySavedInAnEarlierRunIsReadFromTheServerBeforeANewFileIsLockedWithIt() = runTest {
        savedFromAnEarlierRun(oldKey)
        server = grant(newKey)

        val locked = lockNewFile()

        assertTrue(opensWith(locked, newKey), "locked with the drive's current key")
        assertEquals(EncryptionType.ZkDrive, locked.second.type)
        assertEquals(1, reads)
    }

    @Test
    fun aKeyReadMoreThanAMinuteAgoIsReadAgain() = runTest {
        ZkKeyStore.saveAppKey(appKey)
        ZkKeyStore.openDriveKeys(grant(oldKey))
        server = grant(newKey)

        clock += 59.seconds
        assertTrue(opensWith(lockNewFile(), oldKey), "within the minute the key read then holds")
        assertEquals(0, reads)

        clock += 2.seconds
        assertTrue(opensWith(lockNewFile(), newKey))
        assertEquals(1, reads)
    }

    @Test
    fun filesLockedAtTheSameTimeShareOneRead() = runTest {
        savedFromAnEarlierRun(oldKey)
        val answer = CompletableDeferred<SecurityContext>()
        ZkKeyStore.useServer(fetchGrant = { reads++; answer.await() }, resync = {})

        val locks = List(3) { async { lockNewFile() } }
        yield()
        answer.complete(grant(newKey))

        assertTrue(locks.awaitAll().all { opensWith(it, newKey) })
        assertEquals(1, reads)
    }

    @Test
    fun filesWaitingOnAReadThatFailsShareItsFailure() = runTest {
        savedFromAnEarlierRun(oldKey)
        val answer = CompletableDeferred<SecurityContext>()
        ZkKeyStore.useServer(fetchGrant = { reads++; answer.await() }, resync = {})

        val locks = List(3) { async { runCatching { lockNewFile() } } }
        yield()
        answer.completeExceptionally(unreachable())

        assertTrue(locks.awaitAll().all { it.exceptionOrNull() is NetworkException })
        assertEquals(1, reads, "one timeout, not one per file")
    }

    @Test
    fun filesLockedOneAfterAnotherShareAFailureForTenSeconds() = runTest {
        savedFromAnEarlierRun(oldKey)

        repeat(3) { assertFailsWith<NetworkException> { lockNewFile() } }
        assertEquals(1, reads)

        clock += 11.seconds
        server = grant(newKey)
        assertTrue(opensWith(lockNewFile(), newKey))
        assertEquals(2, reads)
    }

    @Test
    fun whenTheServerCantBeReachedTheSavedKeyIsNotUsed() = runTest {
        savedFromAnEarlierRun(oldKey)

        val failure = assertFailsWith<NetworkException> { lockNewFile() }

        assertTrue(failure.isTransientNetworkFailure(), "the upload stays queued and is retried")
    }

    @Test
    fun aServerErrorIsThrownAsItIsNotAsANetworkFailure() = runTest {
        savedFromAnEarlierRun(oldKey)
        ZkKeyStore.useServer(fetchGrant = { throw ServerException(500, null, null) }, resync = {})

        val failure = assertFailsWith<ZkKeyStore.DriveKeysNotRead> { lockNewFile() }

        assertTrue(failure.cause is ServerException)
        assertEquals(false, failure.isTransientNetworkFailure(), "the outbox charges it rather than stall every upload")
    }

    @Test
    fun aServerAnswerIsNotHeldLikeANetworkFailure() = runTest {
        savedFromAnEarlierRun(oldKey)
        ZkKeyStore.useServer(fetchGrant = { reads++; throw ServerException(500, null, null) }, resync = {})
        assertFailsWith<ZkKeyStore.DriveKeysNotRead> { lockNewFile() }

        ZkKeyStore.useServer(fetchGrant = { reads++; grant(newKey) }, resync = {})
        assertTrue(opensWith(lockNewFile(), newKey), "the server answered: the next file reads again")
        assertEquals(2, reads)
    }

    @Test
    fun aNotFoundFromTheKeyReadIsNotTakenForTheUploadsOwnAnswer() = runTest {
        savedFromAnEarlierRun(oldKey)
        ZkKeyStore.useServer(fetchGrant = { throw NotFoundException() }, resync = {})

        val failure = assertFailsWith<ZkKeyStore.DriveKeysNotRead> { lockNewFile() }

        assertTrue(failure.cause is NotFoundException, "the outbox would drop an upload for its own 404")
    }

    @Test
    fun aDriveWhoseKeyTheGrantNoLongerCarriesFailsWithNoDriveKey() = runTest {
        savedFromAnEarlierRun(oldKey)
        server = grant(null)

        assertFailsWith<FileDecryptionException.NoDriveKey> { lockNewFile() }
        assertEquals(setOf(photos), ZkKeyStore.lockedDrives.value, "it asks to sign in again")
    }

    @Test
    fun aDriveMadeAgainAsAnOrdinaryOneIsLockedWithTheSharedSecret() = runTest {
        savedFromAnEarlierRun(oldKey)
        server = grant(null, zeroKnowledge = false)

        val (fileKey, locked) = lockNewFile()

        assertEquals(EncryptionType.Aes, locked.type)
        assertEquals(fileKey, locked.open(photos, SecureByteArray(sharedSecret)))
    }

    @Test
    fun aReadThatEndsAfterSignOutDoesNotBringTheKeysBack() = runTest {
        savedFromAnEarlierRun(oldKey)
        val answer = CompletableDeferred<SecurityContext>()
        ZkKeyStore.useServer(fetchGrant = { answer.await() }, resync = {})

        val lock = async { runCatching { lockNewFile() } }
        yield()
        ZkKeyStore.clear()
        answer.complete(grant(newKey))

        assertTrue(lock.await().exceptionOrNull() is ZkKeyStore.DriveKeysNotRead, "not the photo's or the upload's failure")
        assertEquals(null, ZkKeyStore.driveKey(photos))
        assertEquals(false, ZkKeyStore.isZeroKnowledge(photos))
    }

    @Test
    fun aFileBeingReadIsTransformedWithTheSavedKeyWithoutWaitingOnTheServer() = runTest {
        savedFromAnEarlierRun(oldKey)
        server = grant(newKey)
        val fileKey = KeyHeader.newRandom16()
        val sealed = EncryptedKeyHeader(encryptionVersion = 1, type = EncryptionType.Sealed, iv = fileKey.iv, encryptedAesKey = ByteArray(0))

        val transform = sealed.zkDriveFormOf(fileKey, photos)

        assertEquals(fileKey, transform.decryptAesToKeyHeader(SecureByteArray(oldKey)))
        assertEquals(0, reads)
    }

    @Test
    fun onlyTransformsLockedWithTheCurrentKeyAreSent() = runTest {
        savedFromAnEarlierRun(oldKey)
        server = grant(newKey)
        suspend fun transform(key: ByteArray) = KeyHeader.newRandom16().let { fileKey ->
            KeyHeaderTransform(Uuid.random(), Uuid.random(), EncryptedKeyHeader.encryptKeyHeaderAes(fileKey, fileKey.iv, SecureByteArray(key)))
        }
        val withOld = transform(oldKey)
        val withNew = transform(newKey)

        val offeredAgain = mutableListOf<KeyHeaderTransform>()
        SealedKeyHeaders.dropped = { _, gone -> offeredAgain += gone }

        assertEquals(listOf(withNew), transformsLockedWithCurrentKey(photos, listOf(withOld, withNew)))
        assertEquals(1, reads, "the current key is read before sending")
        assertEquals(listOf(withOld), offeredAgain, "a file's next read queues it again")

        server = grant(null)
        clock += 61.seconds
        assertEquals(emptyList(), transformsLockedWithCurrentKey(photos, listOf(withNew)), "a drive this device can no longer open sends none")
    }

    @Test
    fun aResyncThatFailsDoesNotFailTheRead() = runTest {
        savedFromAnEarlierRun(oldKey)
        server = grant(newKey)
        ZkKeyStore.useServer(fetchGrant = { server!! }, resync = { error("no sync here") })

        assertTrue(opensWith(lockNewFile(), newKey))
    }

    @Test
    fun aDriveWhoseKeyChangedIsSyncedAgainFromTheStart() = runTest {
        savedFromAnEarlierRun(oldKey)

        server = grant(oldKey)
        ZkKeyStore.reread()
        assertEquals(emptyList(), resynced, "the same key reads nothing again")

        server = grant(newKey)
        ZkKeyStore.reread()
        assertEquals(listOf(photos), resynced, "files the old key couldn't open were skipped")
    }

    @Test
    fun aDriveWhoseKeyChangedWhenAFileWasLockedIsSyncedAgainToo() = runTest {
        savedFromAnEarlierRun(oldKey)
        server = grant(newKey)

        lockNewFile()

        assertEquals(listOf(photos), resynced)
    }

    @Test
    fun aKeyThatArrivesIsSyncedAgainAsBefore() = runTest {
        savedFromAnEarlierRun(oldKey)
        server = grant(null)
        ZkKeyStore.reread()
        assertNotEquals(emptySet(), ZkKeyStore.lockedDrives.value)

        server = grant(newKey)
        ZkKeyStore.reread()

        assertEquals(listOf(photos), resynced)
    }
}
