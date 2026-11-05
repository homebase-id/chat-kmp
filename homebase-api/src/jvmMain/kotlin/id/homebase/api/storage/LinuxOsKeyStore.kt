package id.homebase.api.storage

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference

internal class SecretServiceException(message: String) : Exception(message)

private val NO_SERVICE_MARKERS =
    listOf(
        "ServiceUnknown",
        "NameHasNoOwner",
        "org.freedesktop.DBus.Error.NoServer",
        "Cannot autolaunch D-Bus",
        "DBUS_SESSION_BUS_ADDRESS",
        "Could not connect",
        "No such file or directory",
    )

internal fun classifySecretServiceFailure(message: String): OsKeyRead =
    if (NO_SERVICE_MARKERS.any { message.contains(it) }) {
        OsKeyRead.NoStore(SecretServiceException(message))
    } else {
        OsKeyRead.Unreachable(SecretServiceException(message))
    }

@Structure.FieldOrder("name", "type")
internal open class SecretSchemaAttribute : Structure() {
    @JvmField var name: String? = null
    @JvmField var type: Int = 0
}

@Structure.FieldOrder("name", "flags", "attributes", "reserved", "reserved1", "reserved2", "reserved3", "reserved4", "reserved5", "reserved6", "reserved7")
internal class SecretSchema : Structure() {
    @JvmField var name: String? = null
    @JvmField var flags: Int = 0
    @JvmField var attributes: Array<SecretSchemaAttribute> = Array(32) { SecretSchemaAttribute() }
    @JvmField var reserved: Int = 0
    @JvmField var reserved1: Pointer? = null
    @JvmField var reserved2: Pointer? = null
    @JvmField var reserved3: Pointer? = null
    @JvmField var reserved4: Pointer? = null
    @JvmField var reserved5: Pointer? = null
    @JvmField var reserved6: Pointer? = null
    @JvmField var reserved7: Pointer? = null
}

private interface LibSecret : Library {
    fun secret_password_store_sync(schema: SecretSchema, collection: String, label: String, password: String, cancellable: Pointer?, error: PointerByReference, vararg attributes: Any?): Boolean
    fun secret_password_lookup_sync(schema: SecretSchema, cancellable: Pointer?, error: PointerByReference, vararg attributes: Any?): Pointer?
    fun secret_password_clear_sync(schema: SecretSchema, cancellable: Pointer?, error: PointerByReference, vararg attributes: Any?): Boolean
    fun secret_password_free(password: Pointer?)
    fun secret_service_disconnect()
    fun secret_collection_for_alias_sync(service: Pointer?, alias: String, flags: Int, cancellable: Pointer?, error: PointerByReference): Pointer?
    fun secret_collection_get_locked(collection: Pointer): Boolean
}

private interface GLib : Library {
    fun g_error_free(error: Pointer)
    fun g_object_unref(obj: Pointer)
}

private const val RETRIES = 4
private const val COLLECTION_DEFAULT = "default"

/**
 * Secret Service over libsecret. The versioned soname is used because the unversioned one ships only with -dev packages.
 * Retries with a fresh session because KDE's ksecretd fails a share of its DH key exchanges.
 */
internal class LinuxOsKeyStore(private val service: String, private val account: String) : OsKeyStore {
    private val lib by lazy { Native.load("libsecret-1.so.0", LibSecret::class.java) }
    private val glib by lazy { Native.load("libglib-2.0.so.0", GLib::class.java) }
    private val gobject by lazy { Native.load("libgobject-2.0.so.0", GLib::class.java) }

    private val schema by lazy {
        SecretSchema().apply {
            name = "id.homebase.SecureStorage"
            flags = 0
            attributes[0].name = "service"
            attributes[1].name = "account"
        }
    }

    private fun errorMessage(error: PointerByReference): String? {
        val err = error.value ?: return null
        val message = err.getPointer(8)?.getString(0) ?: "unknown error"
        glib.g_error_free(err)
        return message
    }

    private fun <T> attempt(block: (PointerByReference) -> T): Pair<T?, String?> {
        var last: String? = null
        repeat(RETRIES) {
            val error = PointerByReference()
            val result = block(error)
            val message = errorMessage(error)
            if (message == null) return result to null
            last = message
            lib.secret_service_disconnect()
        }
        return null to last
    }

    private fun defaultCollectionLocked(): Boolean {
        val error = PointerByReference()
        val collection = lib.secret_collection_for_alias_sync(null, COLLECTION_DEFAULT, 0, null, error)
        errorMessage(error)?.let { throw SecretServiceException(it) }
        if (collection == null) return false
        return try {
            lib.secret_collection_get_locked(collection)
        } finally {
            gobject.g_object_unref(collection)
        }
    }

    override fun read(): OsKeyRead = try {
        val (pointer, failure) = attempt { error ->
            lib.secret_password_lookup_sync(schema, null, error, "service", service, "account", account, null)
        }
        when {
            failure != null -> classifySecretServiceFailure(failure)
            pointer != null -> try {
                OsKeyRead.Found(pointer.getString(0))
            } finally {
                lib.secret_password_free(pointer)
            }
            // A dismissed unlock prompt returns NULL with no error, which must not read as absent.
            defaultCollectionLocked() -> OsKeyRead.Unreachable(SecretServiceException("default collection is locked"))
            else -> OsKeyRead.Absent
        }
    } catch (e: UnsatisfiedLinkError) {
        OsKeyRead.NoStore(e)
    } catch (e: NoClassDefFoundError) {
        OsKeyRead.NoStore(e)
    } catch (e: Exception) {
        OsKeyRead.Unreachable(e)
    }

    override fun write(value: String) {
        val (_, failure) = attempt { error ->
            lib.secret_password_store_sync(schema, COLLECTION_DEFAULT, "Homebase", value, null, error, "service", service, "account", account, null)
        }
        if (failure != null) throw SecretServiceException(failure)
    }

    override fun delete() {
        val (_, failure) = attempt { error ->
            lib.secret_password_clear_sync(schema, null, error, "service", service, "account", account, null)
        }
        if (failure != null) throw SecretServiceException(failure)
    }

    override fun resetSession() {
        try {
            lib.secret_service_disconnect()
        } catch (_: LinkageError) {
            // no libsecret, so there is no session to reset
        }
    }
}
