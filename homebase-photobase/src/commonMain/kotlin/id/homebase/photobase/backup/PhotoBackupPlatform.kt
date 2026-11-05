package id.homebase.photobase.backup

import id.homebase.api.file.FileOperationsProvider

class PhotoBackupPlatform(val source: DeviceChangeSource, val probe: BackupDeviceProbe)

/** Null on desktop and web, which show the gallery only. */
expect fun createPhotoBackupPlatform(fileOps: FileOperationsProvider): PhotoBackupPlatform?
