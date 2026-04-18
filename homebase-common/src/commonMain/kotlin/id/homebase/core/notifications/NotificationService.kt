package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import com.mmk.kmpnotifier.notification.NotifierManager
import com.mmk.kmpnotifier.notification.PayloadData
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.notifications.PushNotificationApi
import id.homebase.api.client.notifications.PushSubscriptionResponse
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.config.AppConfig
import id.homebase.core.config.COMMUNITY_APP_ID
import id.homebase.core.config.FEED_APP_ID
import id.homebase.core.config.MAIL_APP_ID
import id.homebase.core.config.OWNER_APP_ID
import id.homebase.core.navigation.ActiveConversation
import id.homebase.core.settings.UserPreferences
import id.homebase.core.util.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

enum class SubscriptionVerificationStatus {
    OK, NOT_REGISTERED, NO_LOCAL_TOKEN, TOKEN_MISMATCH
}

data class SubscriptionVerificationDetail(
    val status: SubscriptionVerificationStatus,
    val serverToken: String? = null,
    val friendlyName: String? = null,
)

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

    /** Per-conversation message count for notification summary display. */
    private val conversationMessageCounts = mutableMapOf<String, Int>()

    /** Chime cooldown: suppress alert sounds within this window. */
    private val ALERT_COOLDOWN = 15.minutes
    private var lastAlertMark = TimeSource.Monotonic.markNow() - ALERT_COOLDOWN

    // Channel (not SharedFlow) so a notification tap on cold start is queued until the
    // UI collector attaches, rather than dropped (MutableSharedFlow with replay=0 discards
    // emissions that happen before the first subscriber subscribes).
    private val _navigationEvents = Channel<NotificationNavigationEvent>(Channel.BUFFERED)
    val navigationEvents: Flow<NotificationNavigationEvent> = _navigationEvents.receiveAsFlow()

    private val _inAppNotificationEvents =
        MutableSharedFlow<RichNotificationData>(extraBufferCapacity = 1)
    val inAppNotificationEvents: SharedFlow<RichNotificationData> =
        _inAppNotificationEvents.asSharedFlow()

    /** Set by the UI layer to indicate whether the app is in the foreground. */
    var isAppInForeground: Boolean = false
        set(value) {
            if (value && !field) {
                // App opened — reset alert cooldown so the next background
                // notification plays a sound immediately.
                lastAlertMark = TimeSource.Monotonic.markNow() - ALERT_COOLDOWN
            }
            field = value
        }

    init {
        startListening()
        // Clear message counts when user opens a conversation
        scope.launch {
            ActiveConversation.conversation.collect { id ->
                if (id != null) conversationMessageCounts.remove(id.toString())
            }
        }
    }

    /** Clears the accumulated message count for a conversation (e.g. on mark-as-read). */
    fun clearNotificationCount(conversationId: String) {
        conversationMessageCounts.remove(conversationId)
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

    /**
     * Called from platform FCM service when a new token is received.
     * Forwards to the same listener path as KMPNotifier's built-in service.
     */
    fun onNewFcmToken(token: String) {
        Logger.i(tag = "NotificationService") { "New push token (from FCM service): $token" }
        registerToken(token)
    }

    /**
     * Called from platform FCM service when a message is received.
     * Since DriveFcmService overrides KMPNotifier's MyFirebaseMessagingService,
     * we handle the payload directly here.
     */
    fun onFcmMessageReceived(title: String?, body: String?, data: Map<String, String>) {
        Logger.i(tag = "NotificationService") {
            "FCM message received — title=$title body=$body data=$data"
        }
        handleIncomingPayload(data)
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

                // Suppress chat notifications when user is on the conversation list
                // or viewing the same conversation — they can already see the updates
                val conversationId = resolveConversationId(notification)
                val activeConversationId = ActiveConversation.conversation.value?.toString()
                val isOnChatListScreen = ActiveConversation.isDisplayingChatList.value
                if (isAppInForeground && conversationId != null &&
                    (isOnChatListScreen || conversationId == activeConversationId)
                ) {
                    Logger.d(tag = "NotificationService") {
                        "Suppressing notification — user is on chat screen"
                    }
                    conversationMessageCounts.remove(conversationId)
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

                // Track per-conversation message count for summary display
                val messageCount = if (conversationId != null) {
                    val count = (conversationMessageCounts[conversationId] ?: 0) + 1
                    conversationMessageCounts[conversationId] = count
                    count
                } else 1

                // Override body with count summary when multiple messages accumulated
                val finalBody = if (messageCount > 1) {
                    "$messageCount new messages"
                } else {
                    displayBody
                }

                // Chime cooldown: suppress alert sound if one played recently
                val shouldAlert = lastAlertMark.elapsedNow() >= ALERT_COOLDOWN

                // Determine notification channel based on app type
                val channelId = resolveChannelId(notification.options.appId)

                // Build payload data for round-tripping through notification tap
                val payloadMap = data.entries.associate { it.key to it.value.toString() }

                val richData = RichNotificationData(
                    notificationId = notification.options.conversationNotificationId,
                    channelId = channelId,
                    conversationId = resolveConversationId(notification),
                    title = displayTitle,
                    body = finalBody,
                    senderName = displayName,
                    senderId = notification.senderId,
                    senderImageBytes = senderImageBytes,
                    timestamp = notification.created,
                    payloadData = payloadMap,
                    silent = !shouldAlert,
                )

                if (isAppInForeground) {
                    // Show in-app banner instead of system notification
                    _inAppNotificationEvents.tryEmit(richData)
                } else if (Platform.osName == "Android") {
                    // Android: display rich notification from app code (no service extension)
                    if (shouldAlert) lastAlertMark = TimeSource.Monotonic.markNow()
                    showRichNotification(richData)
                    BadgeManager.increment()
                } else {
                    // iOS: Notification Service Extension handles background display;
                    // posting here would create a duplicate notification.
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

    /** Extracts conversation ID for chat notifications. */
    private fun resolveConversationId(notification: PushNotification): String? {
        val appId = notification.options.appId
        return if (appId == Uuid.parse(AppConfig.APP_ID).toString()) {
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
            val event = when (appId) {
                Uuid.parse(AppConfig.APP_ID).toString() ->
                    NotificationNavigationEvent.OpenConversation(typeId)

                COMMUNITY_APP_ID ->
                    NotificationNavigationEvent.OpenUrl(
                        "https://${notification.senderId}/apps/community/redirect/${typeId}/${tagId}"
                    )

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
                _navigationEvents.trySend(event)
            }
        } catch (e: Exception) {
            Logger.e(tag = "NotificationService") {
                "Failed to handle notification click: ${e.message}"
            }
        }
    }

    /** Navigate to a specific conversation (used for deep links and share shortcuts). */
    fun navigateToConversation(conversationId: String) {
        _navigationEvents.trySend(NotificationNavigationEvent.OpenConversation(conversationId))
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

    /** Verifies the server-side push subscription against the local FCM token.
     *  Self-healing: if a token mismatch is detected (e.g. FCM rotated the token
     *  after the last registration), the current local token is re-registered
     *  and verification is retried once. */
    suspend fun verifySubscription(): SubscriptionVerificationDetail {
        val localToken = getToken()
        val subscription = api.getSubscription()

        val status = checkSubscription(subscription, localToken)

        // Only self-heal when the server has a stale token (value mismatch).
        // If the server returned null, that's a server-side bug — don't retry.
        if (status == SubscriptionVerificationStatus.TOKEN_MISMATCH
            && localToken != null
            && subscription?.firebaseDeviceToken != null
        ) {
            Logger.i(tag = "NotificationService") {
                "Subscription verification: TOKEN_MISMATCH — re-registering local token"
            }
            try {
                registerTokenSuspend(localToken)
                val updatedLocal = getToken()
                val updated = api.getSubscription()
                val healedStatus = checkSubscription(updated, updatedLocal)
                Logger.i(tag = "NotificationService") { "Subscription verification after re-register: $healedStatus" }
                return SubscriptionVerificationDetail(
                    status = healedStatus,
                    serverToken = updated?.firebaseDeviceToken,
                    friendlyName = updated?.friendlyName,
                )
            } catch (e: Exception) {
                Logger.e(tag = "NotificationService") { "Re-register failed: ${e.message}" }
            }
        }

        Logger.i(tag = "NotificationService") { "Subscription verification: $status" }
        return SubscriptionVerificationDetail(
            status = status,
            serverToken = subscription?.firebaseDeviceToken,
            friendlyName = subscription?.friendlyName,
        )
    }

    private fun checkSubscription(
        subscription: PushSubscriptionResponse?,
        localToken: String?
    ): SubscriptionVerificationStatus = when {
        subscription == null -> SubscriptionVerificationStatus.NOT_REGISTERED
        localToken == null -> SubscriptionVerificationStatus.NO_LOCAL_TOKEN
        subscription.firebaseDeviceToken == null -> SubscriptionVerificationStatus.TOKEN_MISMATCH
        subscription.firebaseDeviceToken != localToken -> SubscriptionVerificationStatus.TOKEN_MISMATCH
        else -> SubscriptionVerificationStatus.OK
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

    /** Fire-and-forget re-registration that runs in the service's own long-lived scope,
     *  surviving ViewModel destruction (e.g. navigation away from login screen). */
    fun reRegisterAsync() {
        scope.launch { reRegister() }
    }
}
