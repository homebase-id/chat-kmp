package id.homebase.api.client.drives

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.NetworkException
import id.homebase.api.client.isTransientNetworkFailure
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.crypto.EncryptedKeyHeader
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

    private class MemoryStorage(val values: MutableMap<String, String> = mutableMapOf()) : ZkKeyStore.Storage {
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }

    private val sharedSecret = ByteArrayUtil.getRndByteArray(16)
    private val appKey = ByteArrayUtil.getRndByteArray(16)
    private val oldKey = ByteArrayUtil.getRndByteArray(16)
    private val newKey = ByteArrayUtil.getRndByteArray(16)
    private val photos = Uuid.random()
    private val clock = TestTimeSource()

    // What the server's grant says now; null when it can't be read.
    private var server: SecurityContext? = null
    private var reads = 0
    private val resynced = mutableListOf<Uuid>()

    @BeforeTest
    fun setUp() {
        ZkKeyStore.storage = MemoryStorage()
        ZkKeyStore.timeSource = clock
        ZkKeyStore.useServer(fetchGrant = { reads++; server }, resync = { resynced += it })
    }

    @AfterTest
    fun reset() {
        ZkKeyStore.clear()
        ZkKeyStore.timeSource = TimeSource.Monotonic
        ZkKeyStore.useServer(fetchGrant = { null }, resync = {})
    }

    private suspend fun grant(key: ByteArray?) = SecurityContext(
        CallerContext(securityLevel = "app"),
        PermissionContext(listOf(PermissionGroup(driveGrants = listOf(DriveGrant(
            permissionedDrive = PermissionedDrive(DriveReference(photos.toString(), Uuid.random().toString()), listOf(DrivePermission.Read, DrivePermission.Write)),
            isZeroKnowledge = true,
            zkAppKeyEncryptedStorageKey = key?.let { Base64.encode(ZkLock.lock(it, appKey)) },
        ))))),
    )

    // The keys a device saved in an earlier run, as a new process finds them.
    private suspend fun savedFromAnEarlierRun(key: ByteArray) {
        val saved = MemoryStorage()
        ZkKeyStore.storage = saved
        ZkKeyStore.saveAppKey(appKey)
        ZkKeyStore.openDriveKeys(grant(key))
        ZkKeyStore.storage = MemoryStorage(saved.values.toMutableMap())
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
    fun whenTheServerCantBeReachedTheSavedKeyIsNotUsed() = runTest {
        savedFromAnEarlierRun(oldKey)
        server = null

        val failure = assertFailsWith<NetworkException> { lockNewFile() }

        assertTrue(failure.isTransientNetworkFailure(), "the upload stays queued and is retried")
    }

    @Test
    fun aDriveWhoseKeyTheGrantNoLongerCarriesFailsWithNoDriveKey() = runTest {
        savedFromAnEarlierRun(oldKey)
        server = grant(null)

        assertFailsWith<FileDecryptionException.NoDriveKey> { lockNewFile() }
        assertEquals(setOf(photos), ZkKeyStore.lockedDrives.value, "it asks to sign in again")
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
