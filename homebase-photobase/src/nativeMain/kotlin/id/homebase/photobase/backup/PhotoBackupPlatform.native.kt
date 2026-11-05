package id.homebase.photobase.backup

import id.homebase.api.file.FileOperationsProvider

actual fun createPhotoBackupPlatform(fileOps: FileOperationsProvider): PhotoBackupPlatform? =
    PhotoBackupPlatform(
        source = IosDeviceChangeSource(fileOps),
        probe = IosBackupDeviceProbe(),
        triggers = IosBackupTriggers(),
        uploader = { BackgroundSessionUploader(it.outbox, it.requests, it.source, it.uploadProvider, it.fileOps, it.wifiOnly) },
        listenForUploadResults = ::listenForBackgroundUploadResults,
    )
