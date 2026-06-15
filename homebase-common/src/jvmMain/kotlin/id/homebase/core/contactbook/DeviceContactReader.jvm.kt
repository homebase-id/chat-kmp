package id.homebase.core.contactbook

// Desktop has no native address book. Import is a mobile-only feature; the UI
// hides its entry point via [isDeviceContactsSupported].
actual suspend fun readDeviceContacts(): List<DeviceContact> = emptyList()

actual fun isDeviceContactsSupported(): Boolean = false
