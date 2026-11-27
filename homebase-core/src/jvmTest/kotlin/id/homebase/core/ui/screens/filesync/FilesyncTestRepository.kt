package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDownloadService
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.core.TestTempDirs
import id.homebase.core.testPayloadReader
import io.ktor.client.HttpClient
import okio.FileSystem
import java.nio.file.Files

internal fun testFilesyncRepository(http: HttpClient, credentials: CredentialsManager): FilesyncRepository {
    val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, TestTempDirs.dir("filesync").toString())
    val cache = DriveFileProviderCached(http, credentials, fileOps)
    val files = DriveFileProvider(http, credentials, cache)
    return FilesyncRepository(DriveQueryProvider(http, credentials), PayloadDownloadService(testPayloadReader(http, credentials, cache, fileOps, own = files), fileOps), fileOps, files, credentials)
}
