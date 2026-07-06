package id.homebase.chat.services

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.chat.services.outbox.OptimisticWriterPort
import id.homebase.upload.PayloadBundleEncryptor
import id.homebase.upload.PayloadCacheSeeder
import id.homebase.upload.UploadService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow

/**
 * Builds a real [UploadService] over a fixture's existing outbox / optimistic-writer / encryptor,
 * so a sender migrated onto UploadService still enqueues into the same inspectable in-memory
 * outbox the fixture already owns. The cache seeder is constructed but never invoked by the
 * migrated conversation-create path (it uses `seedCache = false`); it only needs a well-formed
 * [DriveFileProvider], wired here over a MockEngine that 500s (never called).
 */
fun buildTestUploadService(
    outboxSync: OutboxSync,
    optimisticWriter: OptimisticWriter,
    encryptor: PayloadBundleEncryptor,
    credentialsManager: CredentialsManager,
): UploadService {
    val httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })
    val cache = DriveFileProviderCached(httpClient, credentialsManager, NoopUploadFileOps())
    val driveFileProvider = DriveFileProvider(httpClient, credentialsManager, cache)
    return UploadService(
        encryptor = encryptor,
        outboxSync = outboxSync,
        optimisticWriter = OptimisticWriterPort(optimisticWriter),
        payloadCacheSeeder = PayloadCacheSeeder(driveFileProvider, NoopUploadFileOps()),
    )
}

/** No file IO is expected; only [getCacheDirectory] returns a real path (read eagerly by the Coil cache setup). */
private class NoopUploadFileOps : FileOperationsProvider {
    private val cacheDir = java.nio.file.Files.createTempDirectory("hb-chat-uploadsvc-test").toString()
    private fun nope(): Nothing = error("no file IO expected in test UploadService")
    override fun openFileInput(path: String) = nope()
    override suspend fun readFileBytes(path: String) = nope()
    override fun deleteTempFile(path: String) = nope()
    override fun getCacheDirectory(): String = cacheDir
    override fun getFileSize(path: String) = nope()
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String) = nope()
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String) = nope()
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = nope()
}
