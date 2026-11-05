package id.homebase.photobase.backup

import id.homebase.api.ActivityProvider
import id.homebase.api.file.FileOperationsProvider

actual fun createPhotoBackupPlatform(fileOps: FileOperationsProvider): PhotoBackupPlatform? {
    val context = ActivityProvider.requireApplicationContext()
    return PhotoBackupPlatform(AndroidDeviceChangeSource(context), AndroidBackupDeviceProbe(context))
}
