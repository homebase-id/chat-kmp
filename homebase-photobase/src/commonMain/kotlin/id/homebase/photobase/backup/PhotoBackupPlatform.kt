package id.homebase.photobase.backup

import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.CoroutineScope

class BackgroundUploaderContext(
    val outbox: OutboxBackupUploader,
    val requests: BackupRequestBuilder,
    val source: DeviceChangeSource,
    val uploadProvider: DriveUploadProvider,
    val fileOps: FileOperationsProvider,
    val wifiOnly: () -> Boolean,
)

class PhotoBackupPlatform(
    val source: DeviceChangeSource,
    val probe: BackupDeviceProbe,
    val triggers: BackupTriggers,
    /** Null: everything goes through the Outbox. */
    val uploader: ((BackgroundUploaderContext) -> BackupUploader)? = null,
    /** Starts delivering results from uploads that outlive the process. */
    val listenForUploadResults: ((BackupLedger, FileOperationsProvider, CoroutineScope) -> Unit)? = null,
)

/** Null on desktop and web, which show the gallery only. */
expect fun createPhotoBackupPlatform(fileOps: FileOperationsProvider): PhotoBackupPlatform?
