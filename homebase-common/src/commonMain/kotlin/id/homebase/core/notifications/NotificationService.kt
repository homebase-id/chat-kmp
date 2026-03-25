package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import com.mmk.kmpnotifier.notification.NotifierManager
import com.mmk.kmpnotifier.notification.PayloadData
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.notifications.PushNotificationApi
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.config.AppConfig
import id.homebase.core.config.COMMUNITY_APP_ID
import id.homebase.core.config.FEED_APP_ID
import id.homebase.core.config.MAIL_APP_ID
import id.homebase.core.config.OWNER_APP_ID
import id.homebase.core.settings.UserPreferences
import id.homebase.core.util.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

/**
 * Central notification service that wraps KMPNotifier and handles incoming push/local
 * notifications. Register as a singleton in Koin.
 */
class NotificationService(
    private val api: PushNotificationApi,
    private val scope: CoroutineScope,
    private val profileProvider: PublicProfileProviderCached,
    private val userPreferences: UserPreferences,
    private val credentialsManager: CredentialsManager,
) {

    private var isListening = false
    private val richDisplayer = RichNotificationDisplayer()

    private val _navigationEvents = MutableSharedFlow<NotificationNavigationEvent>(replay = 1)
    val navigationEvents: SharedFlow<NotificationNavigationEvent> = _navigationEvents.asSharedFlow()

    private val _inAppNotificationEvents = MutableSharedFlow<RichNotificationData>(extraBufferCapacity = 1)
    val inAppNotificationEvents: SharedFlow<RichNotificationData> = _inAppNotificationEvents.asSharedFlow()

    /** Set by the UI layer to suppress notifications for the currently viewed conversation. */
    var activeConversationId: String? = null

    /** Set by the UI layer to indicate whether the app is in the foreground. */
    var isAppInForeground: Boolean = false

    init {
        startListening()
    }

    /**
     * Initializes notification listeners. Call after NotifierManager.initialize() has been called
     * on the platform side.
     */
    fun startListening() {
        if (isListening) return
        isListening = true

        NotifierManager.addListener(object : NotifierManager.Listener {
            override fun onNewToken(token: String) {
                super.onNewToken(token)
                Logger.i(tag = "NotificationService") { "New push token: $token" }
                registerToken(token)
            }

            override fun onPushNotificationWithPayloadData(
                title: String?, body: String?, data: PayloadData
            ) {
                super.onPushNotificationWithPayloadData(title, body, data)
                Logger.i(tag = "NotificationService") {
                    "Push received — title=$title body=$body data=$data"
                }
                handleIncomingPayload(data)
            }

            override fun onNotificationClicked(data: PayloadData) {
                Logger.i(tag = "NotificationService") { "Notification clicked: $data" }
                handleNotificationClicked(data)
            }

            override fun onPushNotification(title: String?, body: String?) {
                super.onPushNotification(title, body)
                Logger.i(tag = "NotificationService") {
                    "Push received — title=$title body=$body"
                }
            }

            override fun onPayloadData(data: PayloadData) {
                super.onPayloadData(data)
                Logger.i(tag = "NotificationService") { "Payload received: $data" }
                handleIncomingPayload(data)
            }
        })

        NotifierManager.setLogger { message -> Logger.d(tag = "KMPNotifier") { message } }
    }

    private fun registerToken(token: String) {
        scope.launch {
            val maxAttempts = 5
            var attempt = 0
            while (attempt < maxAttempts) {
                if (!credentialsManager.hasActiveCredentials()) {
                    Logger.i(tag = "NotificationService") {
                        "Not authenticated — skipping token registration"
                    }
                    return@launch
                }
                try {
                    registerTokenSuspend(token)
                    return@launch
                } catch (e: Exception) {
                    attempt++
                    Logger.e(tag = "NotificationService") {
                        "Failed to register token (attempt $attempt/$maxAttempts): ${e.message}"
                    }
                    if (attempt < maxAttempts) {
                        val delayMs = (1000L * (1 shl (attempt - 1))).coerceAtMost(16_000L)
                        Logger.i(tag = "NotificationService") {
                            "Retrying token registration in ${delayMs}ms..."
                        }
                        delay(delayMs)
                    }
                }
            }
            Logger.e(tag = "NotificationService") {
                "Token registration failed after $maxAttempts attempts"
            }
        }
    }

    private suspend fun registerTokenSuspend(token: String) {
        val platformName = Platform.osName
        val friendlyName = "${Platform.osName} | ${Platform.osVersion}"

        Logger.i(tag = "NotificationService") {
            "Registering token with server... ($friendlyName)"
        }
        api.subscribe(
            deviceToken = token, devicePlatform = platformName, friendlyName = friendlyName
        )
        Logger.i(tag = "NotificationService") { "Token registered successfully" }
    }

    /** Parses the raw payload data map and creates a local notification display. */
    private fun handleIncomingPayload(data: PayloadData) {
        scope.launch {
            try {
                val dataString = data["data"] as? String ?: return@launch
                val notification = OdinSystemSerializer.deserialize<PushNotification>(dataString)

                if (notification.options.silent) return@launch

                // Suppress notification if the user is viewing the same conversation
                val conversationId = resolveConversationId(notification)
                if (isAppInForeground && conversationId != null &&
                    conversationId == activeConversationId
                ) {
                    Logger.d(tag = "NotificationService") {
                        "Suppressing notification — user is viewing conversation $conversationId"
                    }
                    return@launch
                }

                // Resolve sender display name from public profile (with timeout/fallback)
                val displayName = resolveSenderName(notification.senderId)

                // Fetch sender avatar for rich notification display
                val senderImageBytes = try {
                    withTimeout(5_000) {
                        profileProvider.getPublicImage(OdinId(notification.senderId))
                    }
                } catch (_: Exception) {
                    null
                }

                // Attempt to decrypt notification body (placeholder for future encrypted support)
                val decryptedMessage = decryptNotificationBody(notification)

                val appName = notification.appDisplayName ?: "Homebase"

                // Use decrypted message if available, otherwise format from payload
                val bodyText = decryptedMessage?.replace(notification.senderId, displayName)
                    ?: NotificationBodyFormer.form(
                        payload = notification,
                        hasMultiple = false,
                        appName = appName,
                        senderName = displayName
                    )

                // Apply privacy content level filtering
                val contentLevel = userPreferences.notificationContentLevel
                val (displayTitle, displayBody) = when (contentLevel) {
                    "name_content_actions" -> Pair(appName, bodyText)
                    "name_only" -> Pair(displayName, "New notification")
                    "no_name_or_content" -> Pair("Homebase", "New notification")
                    else -> Pair(appName, bodyText)
                }

                // Determine notification channel based on app type
                val channelId = resolveChannelId(notification.options.appId)

                // Build payload data for round-tripping through notification tap
                val payloadMap = data.entries.associate { it.key to it.value.toString() }

                val richData = RichNotificationData(
                    notificationId = notification.options.conversationNotificationId,
                    channelId = channelId,
                    conversationId = resolveConversationId(notification),
                    title = displayTitle,
                    body = displayBody,
                    senderName = displayName,
                    senderId = notification.senderId,
                    senderImageBytes = senderImageBytes,
                    timestamp = notification.created,
                    payloadData = payloadMap,
                )

                if (isAppInForeground && richData.conversationId != null) {
                    // Show in-app banner instead of system notification
                    _inAppNotificationEvents.tryEmit(richData)
                } else {
                    showRichNotification(richData)
                    BadgeManager.increment()
                }
            } catch (e: Exception) {
                Logger.e(tag = "NotificationService") {
                    "Failed to parse notification: ${e.message}"
                }
            }
        }
    }

    /** Resolves sender display name from public profile, with timeout and fallback. */
    private suspend fun resolveSenderName(senderId: String): String {
        return try {
            withTimeout(5_000) {
                val profile = profileProvider.getPublicProfile(OdinId(senderId))
                profile?.name?.ifBlank { senderId } ?: senderId
            }
        } catch (_: Exception) {
            senderId
        }
    }

    /**
     * Decrypts the notification body if the backend includes an encrypted payload.
     *
     * TODO: When backend sends keyHeader in PushNotificationPayloadOptions:
     *  1. Extract keyHeader from notification.options.keyHeader
     *  2. Decrypt using EncryptedKeyHeader.decryptAesToKeyHeader(sharedSecret)
     *  3. Use decrypted KeyHeader to decrypt the encryptedBody
     *  4. Return decrypted plaintext message
     *
     * Existing crypto infrastructure:
     *   - EncryptedKeyHeader.decryptAesToKeyHeader() in homebase-api
     *   - KeyHeader.decrypt() / KeyHeader.decryptWithIv() in homebase-api
     *   - AesCbc.decrypt() / AesGcm.decrypt() in homebase-api/crypto
     *
     * For now, falls back to unEncryptedMessage.
     */
    private fun decryptNotificationBody(notification: PushNotification): String? {
        val options = notification.options
        if (options.keyHeader != null && options.encryptedBody != null) {
            // TODO: Implement decryption when backend support is ready:
            // val keyHeader = EncryptedKeyHeader.fromBase64(options.keyHeader)
            //     .decryptAesToKeyHeader(sharedSecret)
            // return keyHeader.decrypt(Base64.decode(options.encryptedBody)).decodeToString()
            Logger.w(tag = "NotificationService") {
                "Encrypted notification body received but decryption not yet implemented"
            }
        }
        return options.unEncryptedMessage
    }

    /** Resolves the notification channel based on the app type. */
    private fun resolveChannelId(appId: String): String = when (appId) {
        AppConfig.APP_ID, MAIL_APP_ID, COMMUNITY_APP_ID -> "messages"
        FEED_APP_ID -> "feed"
        OWNER_APP_ID -> "social"
        else -> "messages"
    }

    /** Extracts conversation ID for chat/community notifications. */
    private fun resolveConversationId(notification: PushNotification): String? {
        val appId = notification.options.appId
        return if (appId == AppConfig.APP_ID || appId == COMMUNITY_APP_ID) {
            notification.options.typeId
        } else null
    }

    /**
     * Handles notification tap — emits navigation event for the UI layer.
     * Called from KMPNotifier's onNotificationClicked callback and also
     * directly from platform code (e.g., MainActivity) for custom notifications.
     */
    fun handleNotificationClicked(data: PayloadData) {
        try {
            val dataString = data["data"] as? String ?: return
            val notification = OdinSystemSerializer.deserialize<PushNotification>(dataString)
            val appId = notification.options.appId
            val typeId = notification.options.typeId
            val tagId = notification.options.tagId
            //TODO: COMMUNITY_APP_ID needs to be use openURL
            val event = when (appId) {
                AppConfig.APP_ID, COMMUNITY_APP_ID ->
                    NotificationNavigationEvent.OpenConversation(typeId)

                OWNER_APP_ID ->
                    NotificationNavigationEvent.OpenUrl(
                        "https://${notification.senderId}/owner/connections"
                    )

                MAIL_APP_ID ->
                    NotificationNavigationEvent.OpenUrl(
                        "https://${notification.senderId}/apps/mail/inbox/$typeId"
                    )

                FEED_APP_ID ->
                    if (tagId.isNotBlank()) {
                        NotificationNavigationEvent.OpenUrl(
                            "https://${notification.senderId}/apps/feed/post/$tagId"
                        )
                    } else {
                        NotificationNavigationEvent.OpenUrl(
                            "https://${notification.senderId}/apps/feed"
                        )
                    }

                else -> null
            }

            if (event != null) {
                _navigationEvents.tryEmit(event)
            }
        } catch (e: Exception) {
            Logger.e(tag = "NotificationService") {
                "Failed to handle notification click: ${e.message}"
            }
        }
    }

    /** Displays a rich notification using platform-specific APIs. */
    private fun showRichNotification(data: RichNotificationData) {
        try {
            richDisplayer.show(data)
        } catch (e: Exception) {
            Logger.e(tag = "NotificationService") {
                "Rich notification failed, falling back: ${e.message}"
            }
            showLocalNotification(
                title = data.title, body = data.body, payloadData = data.payloadData
            )
        }
    }

    /** Displays a local notification using KMPNotifier (fallback). */
    fun showLocalNotification(
        title: String,
        body: String,
        payloadData: Map<String, String> = emptyMap(),
    ) {
        val notifier = NotifierManager.getLocalNotifier()
        notifier.notify(
            id = Random.nextInt(0, Int.MAX_VALUE),
            title = title,
            body = body,
            payloadData = payloadData
        )
    }

    /** Gets the current push notification token, or null if not available. */
    suspend fun getToken(): String? {
        return try {
            NotifierManager.getPushNotifier().getToken()
        } catch (e: Exception) {
            Logger.w(tag = "NotificationService") { "Failed to get push token: ${e.message}" }
            null
        }
    }

    /** Deletes the current push notification token and unsubscribes from server. */
    suspend fun deleteToken() {
        try {
            Logger.i(tag = "NotificationService") { "Unsubscribing token..." }
            api.unsubscribe()
            NotifierManager.getPushNotifier().deleteMyToken()
            Logger.i(tag = "NotificationService") { "Token deleted and unsubscribed" }
        } catch (e: Exception) {
            Logger.w(tag = "NotificationService") { "Failed to delete token/unsubscribe: ${e.message}" }
        }
    }

    /** Re-registers push notifications by deleting and re-fetching the token.
     *  Returns Result.success with the new token, or Result.failure with the error.
     *  Unlike the public deleteToken()/getToken(), errors are NOT swallowed here
     *  so the caller gets proper feedback. */
    suspend fun reRegister(): Result<String?> {
        return try {
            Logger.i(tag = "NotificationService") { "Re-registering: unsubscribing old token..." }
            api.unsubscribe()
            NotifierManager.getPushNotifier().deleteMyToken()

            Logger.i(tag = "NotificationService") { "Re-registering: fetching new token..." }
            val newToken = NotifierManager.getPushNotifier().getToken()
            if (newToken != null) {
                registerTokenSuspend(newToken)
            }
            Result.success(newToken)
        } catch (e: Exception) {
            Logger.e(tag = "NotificationService") { "Re-register failed: ${e.message}" }
            Result.failure(e)
        }
    }
}
