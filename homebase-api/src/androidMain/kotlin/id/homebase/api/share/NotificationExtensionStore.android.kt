package id.homebase.api.share

import id.homebase.api.client.auth.ApiCredentials

actual object NotificationExtensionStore {
    actual fun publishCredentials(credentials: ApiCredentials) {}
    actual fun readCredentials(): ApiCredentials? = null
    actual fun clearCredentials() {}
    actual fun publishContentLevel(level: String) {}
    actual fun readContentLevel(): String? = null
}
