package id.homebase.chat.services
import id.homebase.upload.PayloadBundle

import com.russhwolf.settings.Settings
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.file.SourceUnavailableException
import id.homebase.api.video.VideoPayloadProcessor
import id.homebase.core.settings.UserPreferences
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Fail-soft contract for the encryption-on-send boundary (issue #844, bucket 1).
 *
 * Raw pre-encryption sources are disposable temps. If one is swept by the CacheSweeper,
 * OS-evicted, or has its content://`/`ph:// grant revoked before send, [encryptBundle]
 * must fail soft with a typed [SourceUnavailableException] — never an uncaught read error
 * — and leave no orphaned encrypted temps behind. Because encryption runs strictly before
 * the outbox enqueue, this throw guarantees no doomed outbox row is ever created.
 *
 * Runs the real provider over an in-memory okio [FakeFileSystem] (the [OkioFileOperationsProvider]
 * pattern from CacheSweeperTest), so it exercises the actual read/exists/delete behavior.
 */
class PayloadBundleEncryptionServiceFailSoftTest {

    private val cacheDir = "/tmp/homebase"

    private fun service(fileOps: FileOperationsProvider): PayloadBundleEncryptionService =
        PayloadBundleEncryptionService(
            fileOps = fileOps,
            videoProcessor = VideoPayloadProcessor(fileOps),
            eventBus = EventBus(),
            userPreferences = UserPreferences(InMemorySettings()),
        )

    private fun bundle(vararg payloads: PayloadFile) =
        PayloadBundle(payloads = payloads.toList(), thumbnails = emptyList(), previewThumbs = emptyList())

    private fun imagePayload(key: String, path: String) =
        PayloadFile(key = key, filePath = path, contentType = "image/jpeg")

    private fun FakeFileSystem.encTempCount(): Int =
        listOrNull(cacheDir.toPath())?.count { it.name.startsWith("enc") } ?: 0

    @Test
    fun missingSourceFailsSoftWithTypedException() = runTest {
        val fileOps = OkioFileOperationsProvider(FakeFileSystem(), cacheDir)
        val svc = service(fileOps)

        // Nothing was ever written here — a source swept (or never materialized) before send.
        val missing = "$cacheDir/resolved_gone.jpg"

        assertFailsWith<SourceUnavailableException> {
            svc.encryptBundle(
                Uuid.random(),
                bundle(imagePayload("p0", missing)),
                KeyHeader.newRandom16().aesKey,
                this,
            )
        }
    }

    @Test
    fun presentSourceEncryptsToPreEncryptedTemp() = runTest {
        val fileOps = OkioFileOperationsProvider(FakeFileSystem(), cacheDir)
        val svc = service(fileOps)

        val src = fileOps.writeBytesToTempFile(byteArrayOf(1, 2, 3, 4), "resolved_", ".jpg")
        val result = svc.encryptBundle(
            Uuid.random(),
            bundle(imagePayload("p0", src)),
            KeyHeader.newRandom16().aesKey,
            this,
        )

        assertEquals(1, result.payloads.size)
        assertTrue(result.payloads[0].isPreEncrypted, "payload must be marked pre-encrypted")
        assertTrue(fileOps.sourceExists(result.payloads[0].filePath), "encrypted temp must exist on disk")
    }

    @Test
    fun sweptMidBundleReapsEarlierEncryptedTemp() = runTest {
        val fs = FakeFileSystem()
        val inner = OkioFileOperationsProvider(fs, cacheDir)
        val present = inner.writeBytesToTempFile(byteArrayOf(9, 9, 9), "resolved_", ".jpg")
        val vanishing = inner.writeBytesToTempFile(byteArrayOf(8, 8, 8), "resolved_", ".jpg")

        // The swept-mid-bundle race: `vanishing` passes the up-front probe (call #1) but is
        // gone by the time encryptFile re-checks it (call #2), AFTER the first payload has
        // already been encrypted to a temp. The fail-soft path must reap that orphan.
        val racy = object : FileOperationsProvider by inner {
            private var vanishingProbes = 0
            override suspend fun sourceExists(path: String): Boolean {
                if (path == vanishing) {
                    vanishingProbes++
                    return vanishingProbes == 1
                }
                return inner.sourceExists(path)
            }
        }
        val svc = service(racy)

        assertEquals(0, fs.encTempCount(), "no encrypted temps before the send")

        assertFailsWith<SourceUnavailableException> {
            svc.encryptBundle(
                Uuid.random(),
                bundle(imagePayload("p0", present), imagePayload("p1", vanishing)),
                KeyHeader.newRandom16().aesKey,
                this,
            )
        }

        assertEquals(
            0,
            fs.encTempCount(),
            "the first payload's encrypted temp must be reaped on the fail-soft path (no leak)",
        )
    }
}

/**
 * Minimal in-memory [Settings] so the encryptor's [UserPreferences] dependency can be
 * constructed without a real backing store. (homebase-common has an equivalent test fake,
 * but test source sets aren't shared across modules.) Only the getters [UserPreferences]
 * touches at construction are exercised; the encryptor itself never reads preferences on
 * the non-video path under test.
 */
private class InMemorySettings : Settings {
    private val backing = mutableMapOf<String, Any>()
    override val keys: Set<String> get() = backing.keys.toSet()
    override val size: Int get() = backing.size
    override fun clear() = backing.clear()
    override fun remove(key: String) { backing.remove(key) }
    override fun hasKey(key: String): Boolean = backing.containsKey(key)
    override fun putInt(key: String, value: Int) { backing[key] = value }
    override fun getInt(key: String, defaultValue: Int): Int = backing[key] as? Int ?: defaultValue
    override fun getIntOrNull(key: String): Int? = backing[key] as? Int
    override fun putLong(key: String, value: Long) { backing[key] = value }
    override fun getLong(key: String, defaultValue: Long): Long = backing[key] as? Long ?: defaultValue
    override fun getLongOrNull(key: String): Long? = backing[key] as? Long
    override fun putString(key: String, value: String) { backing[key] = value }
    override fun getString(key: String, defaultValue: String): String = backing[key] as? String ?: defaultValue
    override fun getStringOrNull(key: String): String? = backing[key] as? String
    override fun putFloat(key: String, value: Float) { backing[key] = value }
    override fun getFloat(key: String, defaultValue: Float): Float = backing[key] as? Float ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = backing[key] as? Float
    override fun putDouble(key: String, value: Double) { backing[key] = value }
    override fun getDouble(key: String, defaultValue: Double): Double = backing[key] as? Double ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = backing[key] as? Double
    override fun putBoolean(key: String, value: Boolean) { backing[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = backing[key] as? Boolean ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = backing[key] as? Boolean
}
