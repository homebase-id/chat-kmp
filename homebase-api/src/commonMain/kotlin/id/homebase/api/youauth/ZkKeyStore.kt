package id.homebase.api.youauth

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.FileDecryptionException
import id.homebase.api.client.isTransientNetworkFailure
import id.homebase.api.crypto.ZkLock
import id.homebase.api.storage.SecureStorage
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/** The zero-knowledge keys this device holds: the app's key from sign-in, and the key of each zero-knowledge drive the app is granted. */
object ZkKeyStore {
    private const val TAG = "ZkKeyStore"
    private const val APP_KEY = "zk_app_key"
    private const val DRIVE_KEYS = "zk_drive_keys"
    private const val WRITE_ONLY_KEYS = "zk_drive_write_only_keys"

    // A drive made again has a new key, and the server can't tell a file locked with the old one.
    private val LOCKING_KEY_FRESH = 1.minutes

    // Files locked one after another while the network fails share one failure, not a timeout each.
    private val FAILED_READ_HOLD = 10.seconds

    interface Storage {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    private object Secure : Storage {
        override fun get(key: String) = SecureStorage.get(key)
        override fun put(key: String, value: String) = SecureStorage.put(key, value)
        override fun remove(key: String) = SecureStorage.remove(key)
    }

    @Volatile var storage: Storage = Secure
        set(value) {
            field = value
            drives = null
            writeOnlyKeys = null
            rereadAt = null
            failedRead = null
        }

    internal var timeSource: TimeSource = TimeSource.Monotonic

    @Volatile private var fetchGrant: suspend () -> SecurityContext = { error("ZkKeyStore.useServer wasn't called") }
    @Volatile private var resync: (Uuid) -> Unit = {}
    @Volatile private var rereadAt: TimeMark? = null
    @Volatile private var failedRead: Pair<TimeMark, Exception>? = null
    private val rereading = Mutex()

    // A read still in flight at sign-out must not bring the old identity's keys back.
    @Volatile private var generation = 0
    private val saving = SynchronizedObject()

    private class SignedOutDuringRead : IllegalStateException("Signed out while the drive keys were read")

    /** Any failed key read but a network one, which passes as it is: the server's answer to it isn't the upload's own. */
    class DriveKeysNotRead(cause: Exception) : Exception("The drive keys couldn't be read from the server: ${cause.message}", cause)

    /** Set once per process at startup: [fetchGrant] throws its failure as it is; [resync] doesn't wait. */
    fun useServer(fetchGrant: suspend () -> SecurityContext, resync: (Uuid) -> Unit) {
        this.fetchGrant = fetchGrant
        this.resync = resync
    }

    // Every zero-knowledge drive granted to the app, with its key, or null when this device can't open it.
    @Volatile private var drives: Map<Uuid, ByteArray?>? = null

    // Read once per sealed file: kept in memory, not read back from secure storage each time.
    @Volatile private var writeOnlyKeys: Map<Uuid, ByteArray>? = null

    fun appKey(): ByteArray? = storage.get(APP_KEY)?.let(Base64::decode)

    fun saveAppKey(key: ByteArray) = storage.put(APP_KEY, Base64.encode(key))

    fun isZeroKnowledge(alias: Uuid): Boolean = alias in loadedDrives()

    /** The write-only private key (PKCS8) of the zero-knowledge drive [alias], which opens its sealed key headers. */
    fun writeOnlyPrivateKey(alias: Uuid): ByteArray? = loadedWriteOnlyKeys()[alias]

    /** The key of the zero-knowledge drive [alias], or null when it isn't one or this device can't open it. */
    fun driveKey(alias: Uuid): ByteArray? = loadedDrives()[alias]

    /**
     * The key to lock a new file with on [alias], read from the server within the last minute: a saved key may be out
     * of date, so it throws the read's failure rather than use one. Null when the read shows it isn't zero knowledge.
     */
    suspend fun lockingKey(alias: Uuid): ByteArray? {
        if (!fresh()) rereading.withLock {
            if (fresh()) return@withLock
            failedRead?.let { (at, e) -> if (at.elapsedNow() < FAILED_READ_HOLD) throw e }
            Logger.i(tag = TAG) { "Reading the drive keys from the server before locking a file on $alias" }
            readLocked()
        }
        if (!isZeroKnowledge(alias)) return null
        return driveKey(alias) ?: throw FileDecryptionException.NoDriveKey(alias)
    }

    /** Null when the server's grant can't be read. */
    suspend fun reread(): SecurityContext? = try {
        rereading.withLock { readLocked() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DriveKeysNotRead) {
        if (e.cause is SignedOutDuringRead) Logger.i(tag = TAG) { e.cause!!.message!! }
        else Logger.e(throwable = e, tag = TAG) { e.message!! }
        null
    } catch (e: Exception) {
        Logger.e(throwable = e, tag = TAG) { "The drive keys couldn't be read from the server: ${e.message}" }
        null
    }

    private fun fresh() = rereadAt?.let { it.elapsedNow() < LOCKING_KEY_FRESH } == true

    private suspend fun readLocked(): SecurityContext {
        val read = generation
        val context = try {
            fetchGrant()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!e.isTransientNetworkFailure()) throw DriveKeysNotRead(e)
            synchronized(saving) { if (read == generation) failedRead = timeSource.markNow() to e }
            throw e
        }
        failedRead = null
        val changed = try {
            openDriveKeys(context, keep = { read == generation })
        } catch (e: SignedOutDuringRead) {
            throw DriveKeysNotRead(e)
        }
        // A drive's files read before its key arrived or changed were hidden or skipped, and the cursor is past them.
        changed.forEach {
            Logger.i(tag = TAG) { "The key of zero-knowledge drive $it arrived or changed: syncing it again" }
            try {
                resync(it)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Drive $it couldn't be synced again" }
            }
        }
        return context
    }

    private val locked = MutableStateFlow<Set<Uuid>>(emptySet())
    private val zeroKnowledge = MutableStateFlow<Set<Uuid>>(emptySet())

    /** The zero-knowledge drives this device can't open: it signed in before it could get the app's key. */
    val lockedDrives: StateFlow<Set<Uuid>>
        get() = locked.also { loadedDrives() }

    /** Every zero-knowledge drive granted to the app, whether or not this device can open it. */
    val zeroKnowledgeDrives: StateFlow<Set<Uuid>>
        get() = zeroKnowledge.also { loadedDrives() }

    /**
     * Opens the drive keys [context] carries, each locked with the app's key; a grant that's gone takes its key with
     * it. Returns the drives whose key arrived or changed.
     */
    suspend fun openDriveKeys(context: SecurityContext, keep: () -> Boolean = { true }): Set<Uuid> {
        val before = loadedDrives()
        val appKey = appKey()
        val opened = context.driveGrants
            // A write-only grant gets no copy however often the device signs in: it isn't locked, it deposits.
            .filter { it.zkAppKeyEncryptedStorageKey != null || (it.isZeroKnowledge && DrivePermission.Read in it.permissionedDrive.permission) }
            .associate { grant ->
                val alias = Uuid.parse(grant.permissionedDrive.drive.alias)
                // A zero-knowledge drive the app has no copy for yet stays locked until a sign-in brings one.
                alias to grant.zkAppKeyEncryptedStorageKey?.let { locked -> appKey?.let { openLocked(alias, locked, it, "this device's app key") } }
            }
        val writeOnly = context.driveGrants.mapNotNull { grant ->
            val locked = grant.zkStorageKeyEncryptedWriteOnlyPrivateKey ?: return@mapNotNull null
            val alias = Uuid.parse(grant.permissionedDrive.drive.alias)
            val driveKey = opened[alias] ?: return@mapNotNull null
            openLocked(alias, locked, driveKey, "its drive key")?.let { alias to it }
        }.toMap()
        // Nothing is saved until every key is open, and not at all after a sign-out: clear() takes the same lock.
        synchronized(saving) {
            if (!keep()) throw SignedOutDuringRead()
            save(opened)
            saveWriteOnly(writeOnly)
            rereadAt = timeSource.markNow()
        }
        return opened.filter { (alias, key) -> key != null && !(key contentEquals before[alias]) }.keys
    }

    fun clear() {
        synchronized(saving) { forget() }
    }

    private fun forget() {
        storage.remove(APP_KEY)
        storage.remove(DRIVE_KEYS)
        storage.remove(WRITE_ONLY_KEYS)
        publish(emptyMap())
        writeOnlyKeys = emptyMap()
        rereadAt = null
        failedRead = null
        generation++
    }

    private suspend fun openLocked(alias: Uuid, locked: String, key: ByteArray, keyName: String): ByteArray? =
        try {
            ZkLock.open(Base64.decode(locked), key)
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "A key of zero-knowledge drive $alias didn't open with $keyName" }
            null
        }

    private fun saveWriteOnly(keys: Map<Uuid, ByteArray>) {
        val current = loadedWriteOnlyKeys()
        if (keys.keys == current.keys && keys.all { (alias, key) -> key contentEquals current[alias] }) return
        if (keys.isEmpty()) storage.remove(WRITE_ONLY_KEYS)
        else storage.put(WRITE_ONLY_KEYS, Json.encodeToString(keys.entries.associate { (alias, key) -> alias.toString() to Base64.encode(key) }))
        writeOnlyKeys = keys
    }

    private fun loadedWriteOnlyKeys(): Map<Uuid, ByteArray> = writeOnlyKeys ?: run {
        val stored = storage.get(WRITE_ONLY_KEYS)?.let { Json.decodeFromString<Map<String, String>>(it) }.orEmpty()
        stored.entries.associate { (alias, key) -> Uuid.parse(alias) to Base64.decode(key) }.also { writeOnlyKeys = it }
    }

    private fun save(opened: Map<Uuid, ByteArray?>) {
        val current = loadedDrives()
        if (opened.keys == current.keys && opened.all { (alias, key) -> key contentEquals current[alias] }) return
        storage.put(DRIVE_KEYS, Json.encodeToString(opened.entries.associate { (alias, key) -> alias.toString() to key?.let(Base64::encode) }))
        publish(opened)
    }

    private fun publish(loaded: Map<Uuid, ByteArray?>) {
        drives = loaded
        locked.value = loaded.filterValues { it == null }.keys
        zeroKnowledge.value = loaded.keys
    }

    private fun loadedDrives(): Map<Uuid, ByteArray?> = drives ?: run {
        val stored = storage.get(DRIVE_KEYS)?.let { Json.decodeFromString<Map<String, String?>>(it) }.orEmpty()
        stored.entries.associate { (alias, key) -> Uuid.parse(alias) to key?.let(Base64::decode) }.also(::publish)
    }
}
