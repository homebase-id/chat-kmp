package id.homebase.core.contactbook

// Web has no reliable cross-browser contact-picker; import is mobile-only.
actual suspend fun readDeviceContacts(): List<DeviceContact> = emptyList()

actual fun isDeviceContactsSupported(): Boolean = false
