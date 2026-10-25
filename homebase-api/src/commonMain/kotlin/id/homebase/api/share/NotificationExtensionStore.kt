package id.homebase.api.share

import id.homebase.api.client.auth.ApiCredentials

/**
 * What a push handler outside the app process needs to show real content: the active identity's
 * credentials and the user's notification content level.
 *
 * Contract: a read returns the last value published, or null when none was published or the
 * credentials were cleared. On iOS the values live in the App Group — credentials in its keychain
 * access group — for the Notification Service Extension. Everywhere else the push is handled in
 * the app process, which already has both, so publishing does nothing and reads return null.
 */
expect object NotificationExtensionStore {
    fun publishCredentials(credentials: ApiCredentials)
    fun readCredentials(): ApiCredentials?
    fun clearCredentials()
    fun publishContentLevel(level: String)
    fun readContentLevel(): String?
}
