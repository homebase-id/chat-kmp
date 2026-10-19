package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDownloadService
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.file.OkioFileOperationsProvider
import io.ktor.client.HttpClient
import okio.FileSystem
import java.nio.file.Files

internal fun testFilesyncRepository(http: HttpClient, credentials: CredentialsManager): FilesyncRepository {
    val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, Files.createTempDirectory("filesync").toString())
    val files = DriveFileProvider(http, credentials, DriveFileProviderCached(http, credentials, fileOps))
    return FilesyncRepository(DriveQueryProvider(http, credentials), PayloadDownloadService(files, fileOps), fileOps, files)
}
