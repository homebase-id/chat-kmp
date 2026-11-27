package id.homebase.core.image

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.payload.PayloadReader
import id.homebase.api.client.peer.PeerFileByGlobalTransitProvider
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow

/** Only [getCacheDirectory] is real: the loader's disk caches live under [dir]. */
internal class CacheOnlyFileOps(private val dir: () -> String) : FileOperationsProvider {
    override fun getCacheDirectory() = dir()
    override suspend fun readFileBytes(path: String): ByteArray = error("not used")
    override fun openFileInput(path: String): InputProvider = error("not used")
    override fun deleteTempFile(path: String) = false
    override fun getFileSize(path: String) = 0L
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used")
}

internal fun imageLoaderOver(http: HttpClient, credentials: CredentialsManager, cache: DriveFileProviderCached, fileOps: FileOperationsProvider) =
    HomebaseImageLoader(
        PayloadReader(
            DriveFileProvider(http, credentials, cache),
            PeerFileByGlobalTransitProvider(http, credentials, DriveFileHttpProvider(http, credentials), cache),
            fileOps,
        ),
        fileOps,
    )
