package id.homebase.api.youauth

import co.touchlab.kermit.Logger
import id.homebase.api.crypto.ZkLock
import id.homebase.api.storage.SecureStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/** The zero-knowledge keys this device holds: the app's key from sign-in, and the key of each zero-knowledge drive the app is granted. */
object ZkKeyStore {
    private const val TAG = "ZkKeyStore"
    private const val APP_KEY = "zk_app_key"
    private const val DRIVE_KEYS = "zk_drive_keys"
    private const val WRITE_ONLY_KEYS = "zk_drive_write_only_keys"

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

    private val locked = MutableStateFlow<Set<Uuid>>(emptySet())
    private val zeroKnowledge = MutableStateFlow<Set<Uuid>>(emptySet())

    /** The zero-knowledge drives this device can't open: it signed in before it could get the app's key. */
    val lockedDrives: StateFlow<Set<Uuid>>
        get() = locked.also { loadedDrives() }

    /** Every zero-knowledge drive granted to the app, whether or not this device can open it. */
    val zeroKnowledgeDrives: StateFlow<Set<Uuid>>
        get() = zeroKnowledge.also { loadedDrives() }

    /** Opens the drive keys [context] carries, each locked with the app's key; a grant that's gone takes its key with it. */
    suspend fun openDriveKeys(context: SecurityContext) {
        val appKey = appKey()
        val opened = context.driveGrants
            // A write-only grant gets no copy however often the device signs in: it isn't locked, it deposits.
            .filter { it.zkAppKeyEncryptedStorageKey != null || (it.isZeroKnowledge && DrivePermission.Read in it.permissionedDrive.permission) }
            .associate { grant ->
                val alias = Uuid.parse(grant.permissionedDrive.drive.alias)
                // A zero-knowledge drive the app has no copy for yet stays locked until a sign-in brings one.
                alias to grant.zkAppKeyEncryptedStorageKey?.let { locked -> appKey?.let { openLocked(alias, locked, it, "this device's app key") } }
            }
        save(opened)
        val writeOnly = context.driveGrants.mapNotNull { grant ->
            val locked = grant.zkStorageKeyEncryptedWriteOnlyPrivateKey ?: return@mapNotNull null
            val alias = Uuid.parse(grant.permissionedDrive.drive.alias)
            val driveKey = opened[alias] ?: return@mapNotNull null
            openLocked(alias, locked, driveKey, "its drive key")?.let { alias to it }
        }.toMap()
        saveWriteOnly(writeOnly)
    }

    fun clear() {
        storage.remove(APP_KEY)
        storage.remove(DRIVE_KEYS)
        storage.remove(WRITE_ONLY_KEYS)
        publish(emptyMap())
        writeOnlyKeys = emptyMap()
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
