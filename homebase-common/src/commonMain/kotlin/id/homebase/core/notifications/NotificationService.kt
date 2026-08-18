package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.notifications.PushNotificationApi
import id.homebase.api.client.notifications.PushSubscriptionResponse
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.youauth.YouAuthState
import id.homebase.core.config.AppConfig
import id.homebase.core.config.COMMUNITY_APP_ID
import id.homebase.core.config.FEED_APP_ID
import id.homebase.core.config.MAIL_APP_ID
import id.homebase.core.config.OWNER_APP_ID
import id.homebase.core.config.OWNER_CONNECTION_REQUEST_TYPE_ID
import id.homebase.core.navigation.ActiveConversation
import id.homebase.core.settings.UserPreferences
import id.homebase.core.sync.awaitAuthRestored
import id.homebase.core.util.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
 * Builds the browser-redirect navigation event for a tapped notification from a
 * web-app companion (community, owner, mail, feed). These open the *logged-in*
 * identity's own web app — never the message sender's domain: the companion web
 * clients are served from and authenticated against our own identity, and the
 * author's host has no session for us. Faithful to the RN app, which built these
 * from getIdentity(), and to the in-app Feed WebView (https://{ownDomain}/apps/feed).
 *
 * @param ownDomain the active (logged-in) identity's domain, or null when logged
 *   out / credentials not yet ready — in which case there is nothing to open.
 * @return the [NotificationNavigationEvent.OpenUrl] to emit, or null for a null
 *   domain or a non-companion appId (e.g. chat, which navigates in-app).
 */
internal fun buildCompanionAppUrlEvent(
    appId: String,
    ownDomain: String?,
    typeId: String,
    tagId: String,
): NotificationNavigationEvent.OpenUrl? {
    if (ownDomain.isNullOrBlank()) return null
    return when (appId) {
        COMMUNITY_APP_ID ->
            NotificationNavigationEvent.OpenUrl(
                "https://$ownDomain/apps/community/redirect/$typeId/$tagId"
            )

        OWNER_APP_ID ->
            NotificationNavigationEvent.OpenUrl("https://$ownDomain/owner/connections")

        MAIL_APP_ID ->
            NotificationNavigationEvent.OpenUrl("https://$ownDomain/apps/mail/inbox/$typeId")

        FEED_APP_ID ->
            if (tagId.isNotBlank()) {
                NotificationNavigationEvent.OpenUrl("https://$ownDomain/apps/feed/post/$tagId")
            } else {
                NotificationNavigationEvent.OpenUrl("https://$ownDomain/apps/feed")
            }

        else -> null
    }
}

/** Apps whose notifications open the logged-in identity's own web app in the browser. */
internal val COMPANION_APP_IDS = setOf(COMMUNITY_APP_ID, OWNER_APP_ID, MAIL_APP_ID, FEED_APP_ID)

/**
 * Routes a tapped owner-app connection-request notification to the in-app contact detail for the
 * requester, where it can be reviewed and accepted (with the add-to-circles picker) — instead of
 * the owner web console in the browser, which is where every other owner notification goes via
 * [buildCompanionAppUrlEvent].
 *
 * Unlike the companion URLs, this one is keyed on the *sender*: they're the identity asking to
 * connect, so their contact detail is the thing to open. Their host is never contacted for a
 * session — the screen reads our own pending-request list and their public profile.
 *
 * @return the event to emit, or null when this isn't an owner connection-request tap or the
 *   payload carries no sender — both fall through to the normal companion-URL handling, since
 *   without a domain there is no contact screen to open.
 */
internal fun buildConnectionRequestTapEvent(
    appId: String,
    typeId: String,
    senderId: String?,
): NotificationNavigationEvent.OpenConnectionRequest? {
    if (appId != OWNER_APP_ID || typeId != OWNER_CONNECTION_REQUEST_TYPE_ID) return null
    val sender = senderId?.trim()?.lowercase()?.ifBlank { null } ?: return null
    return NotificationNavigationEvent.OpenConnectionRequest(sender)
}

/**
 * How long a companion-app tap waits for credentials to be restored before
 * giving up. Mirrors BackgroundSyncOrchestrator's AUTH_RESTORE_TIMEOUT: the
 * observed cold-wake restore window is ~12 ms, and 2 s leaves headroom for a
 * slow-disk / contended-Koin-init start without hanging a logged-out tap.
 */
private val COMPANION_AUTH_RESTORE_TIMEOUT = 2.seconds

/**
 * Sender-side placeholder body for chat pushes (ChatMessageSenderService) — not real
 * content. Keep in sync with the same literal in the iOS NotificationServiceExtension.
 */
private const val CONTENTLESS_PLACEHOLDER = "You have a new message"

/** Budget for the in-app message resolve on push receipt (#859) before falling back to the
 *  generic body — a single-message local read or server header fetch must fit comfortably. */
private const val NOTIFICATION_RESOLVE_TIMEOUT_MS = 4_000L

/**
 * Resolves the companion-app redirect event, AWAITING auth restoration so a tap
 * from a killed app isn't dropped while `YouAuthFlowManager.restoreSession()` is
 * still loading credentials — the cold-start counterpart of the background-sync
 * race closed by [awaitAuthRestored]. The own-identity domain is read from the
 * resolved [YouAuthState.Authenticated]; returns null when auth does not resolve
 * to Authenticated within [timeout] (logged out / wedged restore) or the app is
 * not a companion.
 *
 * Extracted as a top-level suspend function so it's unit-testable with virtual
 * time, without constructing NotificationService's dependency graph.
 */
internal suspend fun resolveCompanionAppUrlEvent(
    appId: String,
    authState: StateFlow<YouAuthState>,
    typeId: String,
    tagId: String,
    timeout: Duration,
): NotificationNavigationEvent.OpenUrl? {
    val resolved = awaitAuthRestored(authState, timeout)
    val ownDomain = (resolved as? YouAuthState.Authenticated)?.identity?.domainName
    return buildCompanionAppUrlEvent(appId, ownDomain, typeId, tagId)
}

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
    private val pendingNotificationTap: PendingNotificationTap,
    private val notificationBackend: NotificationBackend,
    private val eventBus: EventBus,
    /**
     * Narrow seam onto [id.homebase.api.youauth.YouAuthFlowManager.authState] so
     * a companion-app tap can await credential restoration (cold-start race).
     * A `StateFlow<YouAuthState>` rather than the whole manager keeps the
     * resolver unit-testable — same pattern as [id.homebase.core.sync.BackgroundSyncOrchestrator].
     */
    private val authState: StateFlow<YouAuthState>,
    /**
     * Resolves real chat message content in-app for the notification body (#859). Optional —
     * null on platforms/builds that don't provide it (iOS NSE, tests), where the generic body
     * is kept. Injected from homebase-chat via [NotificationMessageResolver].
     */
    private val messageResolver: NotificationMessageResolver? = null,
) {

    private var isListening = false
    private val richDisplayer = RichNotificationDisplayer()

    /** Per-conversation message count for notification summary display. */
    private val counts = ConversationNotificationCounts()

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
                if (id != null) clearConversationNotifications(id.toString())
            }
        }
        // Logout: drop per-conversation unread counts and the chime cooldown so the
        // previous identity's notification summary state doesn't carry into the next.
        scope.launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.SessionEnded) reset()
            }
        }
        // Route clicks from platform backends (Nucleus on JVM) through the shared
        // NotificationEntry so every platform's tap path lands in the same place,
        // with the same defensive-sync semantics. Lazy Koin lookup avoids a
        // construction-time circular dep: NotificationEntry holds a
        // NotificationService reference, so this side can only resolve it after
        // Koin has wired both. By the time the click handler fires (in response
        // to a user gesture) Koin has long since completed its graph.
        NotificationClickRouter.handler = { data -> NotificationEntry.fromKoin().onNotificationTappedAsync(data) }
    }

    /** Clears the accumulated message count for a conversation (e.g. on mark-as-read). */
    fun clearNotificationCount(conversationId: String) {
        counts.clear(conversationId)
    }

    /**
     * Clears a single conversation's notification state: forgets its summary
     * count and cancels its posted notification + group summary from the tray.
     * Called when the user taps the conversation's notification or opens the
     * conversation in-app — scoped to one conversation so other senders'
     * notifications are left intact.
     */
    private fun clearConversationNotifications(conversationId: String) {
        counts.clear(conversationId)
        val (messageId, summaryId) = conversationNotificationIds(conversationId)
        BadgeManager.cancelConversationNotifications(messageId, summaryId)
    }

    /** Logout: clear all accumulated per-conversation counts and reset the chime cooldown. */
    fun reset() {
        counts.clearAll()
        BadgeManager.cancelAll()
        lastAlertMark = TimeSource.Monotonic.markNow() - ALERT_COOLDOWN
    }

    /**
     * Initializes notification listeners. Call after the platform backend has
     * been initialized at the entry point (e.g. NotifierManager.initialize()
     * on Android/JVM/iOS).
     */
    fun startListening() {
        if (isListening) return
        isListening = true

        notificationBackend.addListener(object : NotificationListener {
            override fun onNewToken(token: String) {
                Logger.i(tag = "NotificationService") { "New push token: $token" }
                registerToken(token)
            }

            override fun onPushNotificationWithPayloadData(
                title: String?, body: String?, data: PayloadData
            ) {
                Logger.i(tag = "NotificationService") {
                    "Push received — title=$title body=$body data=$data"
                }
                handleIncomingPayload(data)
            }

            override fun onNotificationClicked(data: PayloadData) {
                Logger.i(tag = "NotificationService") { "Notification clicked: $data" }
                // Route through NotificationEntry so iOS taps (which arrive via
                // KMPNotifier) hit the same defensive-sync path as Android /
                // Desktop taps. See NotificationClickRouter.handler comment above.
                NotificationEntry.fromKoin().onNotificationTappedAsync(data)
            }

            override fun onPushNotification(title: String?, body: String?) {
                Logger.i(tag = "NotificationService") {
                    "Push received — title=$title body=$body"
                }
            }

            override fun onPayloadData(data: PayloadData) {
                Logger.i(tag = "NotificationService") { "Payload received: $data" }
                handleIncomingPayload(data)
            }
        })

        notificationBackend.setLogger { message -> Logger.d(tag = "KMPNotifier") { message } }
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
                    counts.clear(conversationId)
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

                // Real content only — not the sender-side placeholder (and not the
                // NotificationBodyFormer fallback used when decryptedMessage is null).
                val hasContent = !decryptedMessage.isNullOrEmpty() &&
                        decryptedMessage != CONTENTLESS_PLACEHOLDER

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
                // Full content is shown at name_content_actions (and the default/unknown case,
                // which fromCode() treats as that level) — never at the redacted levels.
                val showsRealContent =
                    contentLevel != "name_only" && contentLevel != "no_name_or_content"

                // Track per-conversation message count for summary display
                val messageCount = if (conversationId != null) {
                    counts.increment(conversationId)
                } else 1

                // When real content is shown, keep the per-message body and let the Android
                // displayer stack the recent messages (MessagingStyle). Only collapse to a
                // count at the redacted levels, where there's no content to show anyway.
                val finalBody = notificationBody(displayBody, messageCount, showsRealContent)

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
                    hasContent = hasContent,
                    showsRealContent = showsRealContent,
                )

                if (isAppInForeground) {
                    // Show in-app banner instead of system notification
                    _inAppNotificationEvents.tryEmit(richData)
                } else if (Platform.osName.contains("iOS", ignoreCase = true) ||
                    Platform.osName.contains("iPadOS", ignoreCase = true)
                ) {
                    // iOS: Notification Service Extension handles background display;
                    // posting here would create a duplicate notification.
                    BadgeManager.increment()
                } else {
                    // Android + Desktop (Windows/macOS/Linux): display rich notification
                    // from app code. On desktop this routes through Nucleus via
                    // RichNotificationDisplayer.
                    if (shouldAlert) lastAlertMark = TimeSource.Monotonic.markNow()
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
    private suspend fun decryptNotificationBody(notification: PushNotification): String? {
        // In-app resolve+decrypt of the referenced chat message (#859): reuses the same
        // decrypt + typed-preview pipeline as the chat UI via the injected resolver (which lives
        // in homebase-chat). Bounded by a timeout to stay within the push-handler budget; any
        // miss/timeout/failure falls through to the sender-provided generic body.
        val resolver = messageResolver
        if (resolver != null && resolveConversationId(notification) != null) {
            val ids = extractChatTapIds(notification.options.typeId, notification.options.tagId)
            if (ids != null) {
                val (conversationId, messageId) = ids
                val preview = try {
                    withTimeoutOrNull(NOTIFICATION_RESOLVE_TIMEOUT_MS) {
                        resolver.resolvePreview(conversationId, messageId)?.preview
                    }
                } catch (e: Exception) {
                    Logger.w(tag = "NotificationService") {
                        "in-app notification content resolve failed: ${e.message}"
                    }
                    null
                }
                if (!preview.isNullOrBlank()) return preview
            }
        }
        return notification.options.unEncryptedMessage
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
        if (appId != Uuid.parse(AppConfig.APP_ID).toString()) return null
        // Moments posts/comments ride on the chat appId (this is the chat app) but
        // are not conversations. Excluding them keeps moment pushes from being
        // suppressed while the user is on the chat list, counted into a chat
        // conversation's summary, or cancelled as chat notifications.
        if (isMomentsTap(notification.options.typeId, notification.options.tagId)) return null
        return notification.options.typeId
    }

    /** Logs and queues a navigation event onto the buffered nav Channel. */
    private fun emitNavigationEvent(event: NotificationNavigationEvent) {
        Logger.i(tag = "NotificationService") { "navigationEvent emit: $event" }
        _navigationEvents.trySend(event)
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
            // Instrumentation: capture exactly what the backend delivered so we can
            // confirm whether a moments-comment push arrives with its sentinel tagId
            // intact, or whether the server rewrote/validated tagId in transit.
            Logger.i(tag = "NotificationService") {
                "tap payload: appId=$appId typeId=$typeId tagId=$tagId sender=${notification.senderId}"
            }
            val momentsTap = if (appId == Uuid.parse(AppConfig.APP_ID).toString()) {
                resolveMomentsTap(typeId, tagId)
            } else null
            val connectionRequestTap =
                buildConnectionRequestTapEvent(appId, typeId, notification.senderId)
            when {
                // Moments posts/comments ride on the chat appId but are routed to the
                // moments detail (reels) screen, not ChatList. A post carries
                // typeId == tagId; a comment carries the sentinel tagId (and opens with
                // comments expanded). A chat message has distinct conversationId/
                // messageId, so this can't shadow a real chat tap. Checked first since
                // it's a stricter chat-appId case.
                momentsTap != null -> {
                    Logger.i(tag = "NotificationService") {
                        "Moments tap — opening moment=${momentsTap.momentId} " +
                                "openComments=${momentsTap.openComments}"
                    }
                    emitNavigationEvent(
                        NotificationNavigationEvent.OpenMoment(
                            momentId = momentsTap.momentId,
                            openComments = momentsTap.openComments,
                        )
                    )
                }

                appId == Uuid.parse(AppConfig.APP_ID).toString() -> {
                    // Only set the pending tap when BOTH conversationId and
                    // messageId are present — a messageId-less payload is
                    // ambient "new activity" and should not auto-navigate
                    // into a specific conversation. The Channel emission
                    // still fires so AppNavHost pops back to ChatList.
                    val ids = extractChatTapIds(typeId, tagId)
                    if (ids != null) {
                        Logger.i(tag = "NotificationService") {
                            "Setting pendingNotificationTap convo=${ids.first} msg=${ids.second}"
                        }
                        pendingNotificationTap.set(ids.first, ids.second)
                    } else {
                        Logger.i(tag = "NotificationService") {
                            "Chat tap without full (convoId, messageId) — " +
                                    "typeId=$typeId tagId=$tagId; no auto-navigate"
                        }
                    }
                    // Tapping clears only this conversation's notifications + count,
                    // leaving other senders' notifications in the tray.
                    clearConversationNotifications(typeId)
                    emitNavigationEvent(NotificationNavigationEvent.OpenConversation(typeId))
                }

                // An incoming connection request is reviewable in-app (contact detail shows the
                // requester's public profile with Accept/Reject + the circle picker), so it opens
                // there instead of bouncing to the owner web console like the owner app's other
                // notifications. Sits ahead of the companion branch, which OWNER_APP_ID would
                // otherwise claim; a payload with no sender leaves this null and falls through to
                // it unchanged, since without a domain there's no contact to open.
                connectionRequestTap != null -> {
                    Logger.i(tag = "NotificationService") {
                        "Connection-request tap — opening contact detail for " +
                                connectionRequestTap.odinId
                    }
                    emitNavigationEvent(connectionRequestTap)
                }

                appId in COMPANION_APP_IDS -> {
                    // Community, owner, mail and feed open the *logged-in* identity's
                    // own web app in the browser (see buildCompanionAppUrlEvent) —
                    // never the sender's host. Resolve the domain by AWAITING auth
                    // restoration so a tap from a KILLED app isn't dropped while
                    // restoreSession() is still loading credentials (cold-start
                    // race). Emit off the injected scope into the buffered nav
                    // Channel, which queues the event until the UI collector attaches.
                    scope.launch {
                        val event = resolveCompanionAppUrlEvent(
                            appId = appId,
                            authState = authState,
                            typeId = typeId,
                            tagId = tagId,
                            timeout = COMPANION_AUTH_RESTORE_TIMEOUT,
                        )
                        if (event != null) {
                            emitNavigationEvent(event)
                        } else {
                            Logger.w(tag = "NotificationService") {
                                "Companion tap produced no navigation " +
                                        "(credentials unavailable) appId=$appId"
                            }
                        }
                    }
                }

                else -> Logger.w(tag = "NotificationService") {
                    "No navigationEvent produced from click (appId unmatched: $appId)"
                }
            }
        } catch (e: Exception) {
            Logger.e(tag = "NotificationService") {
                "Failed to handle notification click: ${e.message}"
            }
        }
    }

    /** Navigate to a specific conversation (used for deep links and share shortcuts). */
    fun navigateToConversation(
        conversationId: String,
        source: NotificationNavigationEvent.OpenConversation.Source =
            NotificationNavigationEvent.OpenConversation.Source.NotificationTap,
    ) {
        _navigationEvents.trySend(
            NotificationNavigationEvent.OpenConversation(conversationId, source)
        )
    }

    /** Navigate to the moments composer (used for the "New Moment" share deep link). */
    fun navigateToMomentCompose() {
        _navigationEvents.trySend(NotificationNavigationEvent.OpenMomentCompose)
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

    /** Displays a local notification using the platform backend (fallback). */
    fun showLocalNotification(
        title: String,
        body: String,
        payloadData: Map<String, String> = emptyMap(),
    ) {
        notificationBackend.showLocalNotification(
            id = Random.nextInt(0, Int.MAX_VALUE),
            title = title,
            body = body,
            payloadData = payloadData,
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
            notificationBackend.getPushToken()
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
            notificationBackend.deletePushToken()
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
            notificationBackend.deletePushToken()

            Logger.i(tag = "NotificationService") { "Re-registering: fetching new token..." }
            val newToken = notificationBackend.getPushToken()
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

/**
 * Mirrors Android's `Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` bit. Callers
 * pass `intent.flags`; a non-zero AND means the Intent comes from the recents
 * history stack (launcher resume after process death) rather than a fresh
 * notification tap. In that case the launching Intent's notification extras
 * are stale and should not re-fire navigation.
 */
const val FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY: Int = 0x00100000

fun isReplayedFromHistory(flags: Int): Boolean =
    (flags and FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0

/**
 * Outcome of inspecting an Android notification-tap Intent. [Skip] means the
 * Intent is either a recents/launcher replay or doesn't carry the tap marker;
 * either way, the caller must not invoke [NotificationService.handleNotificationClicked].
 * [Process] means the Intent is a fresh, marked tap and the payload should be
 * forwarded to NotificationService.
 */
sealed interface NotificationIntentDecision {
    data object Skip : NotificationIntentDecision
    data class Process(val payload: PayloadData) : NotificationIntentDecision
}

/**
 * Decides whether a notification-tap Intent should be processed. Pure function —
 * no Activity, no Intent — so it's reachable from JVM unit tests. The Android
 * call site reads `intent.flags`, `intent.getBooleanExtra(EXTRA_NOTIFICATION_TAP)`,
 * and `intent.extras` and routes through this.
 *
 * Skip when:
 *  - `flags` carries [FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY] (Android resumed the
 *    activity from recents/launcher and handed back the original launching Intent
 *    — the extras are stale; this is the bug from homebase.log 2026-05-01 08:05:49).
 *  - The Intent doesn't carry the notification-tap marker (deep links, normal app
 *    launches, etc.).
 */
fun decideNotificationIntent(
    flags: Int,
    isMarkedAsTap: Boolean,
    payload: PayloadData,
): NotificationIntentDecision = when {
    isReplayedFromHistory(flags) -> NotificationIntentDecision.Skip
    !isMarkedAsTap -> NotificationIntentDecision.Skip
    else -> NotificationIntentDecision.Process(payload)
}

/**
 * Parses a chat notification's `typeId` (conversation) and `tagId`
 * (message) into Uuids. Returns null if either is missing or malformed
 * — the caller then skips setting the pending tap and the user lands
 * on ChatList without an auto-jump.
 *
 * Extracted for unit testability (handleNotificationClicked has too
 * many collaborators to mock directly).
 */
internal fun extractChatTapIds(typeId: String, tagId: String): Pair<Uuid, Uuid>? {
    val convoUuid = Uuid.parseOrNull(typeId) ?: return null
    val msgUuid = tagId.takeIf { it.isNotBlank() }?.let { Uuid.parseOrNull(it) }
        ?: return null
    return convoUuid to msgUuid
}

/**
 * Well-known sentinel `tagId` that marks a Moments *comment* push, distinguishing
 * it from a moment-*post* push. Both kinds keep `typeId == momentId` (so the moment
 * to open is always recoverable from `typeId`, and notifications coalesce per moment
 * via `typeId.hashCode()`); the post sets `tagId == typeId == momentId` while the
 * comment sets `tagId` to this sentinel. A chat message's `tagId` is a random
 * messageId, so collision with this fixed value is effectively impossible.
 *
 * Shared with the send path ([id.homebase.core.moments.services.MomentsPostSenderService]).
 */
const val MOMENT_COMMENT_TAG_SENTINEL = "00000000-0000-4000-8000-0000c0117e57"

/** What a tapped Moments push resolves to: which moment to open, and whether to expand comments. */
internal data class MomentsTapTarget(val momentId: String, val openComments: Boolean)

/**
 * Resolves a chat-appId push to a Moments tap target, or null when it's an ordinary
 * chat message. Moments must keep the chat appId (the backend rejects a push whose
 * appId isn't the registered app — this *is* the chat app), so they're told apart by
 * their id shape:
 *  - comment → `tagId == `[MOMENT_COMMENT_TAG_SENTINEL]; open the moment (`typeId`)
 *    with the comments sheet expanded.
 *  - post    → `typeId == tagId` (== momentId); open the moment.
 *  - chat    → distinct conversationId (`typeId`) / messageId (`tagId`), neither the
 *    sentinel → null.
 *
 * Used both to route the tap and to keep moments out of chat suppression/counting.
 */
internal fun resolveMomentsTap(typeId: String, tagId: String): MomentsTapTarget? {
    if (typeId.isBlank()) return null
    // Compare the sentinel as a parsed Uuid so a backend round-trip that re-cases or
    // re-formats the GUID still matches. typeId == tagId is a same-payload string
    // compare, so it needs no such normalisation.
    val sentinel = Uuid.parseOrNull(MOMENT_COMMENT_TAG_SENTINEL)
    return when {
        Uuid.parseOrNull(tagId) == sentinel -> MomentsTapTarget(typeId, openComments = true)
        typeId == tagId -> MomentsTapTarget(typeId, openComments = false)
        else -> null
    }
}

/** Convenience predicate over [resolveMomentsTap]. */
internal fun isMomentsTap(typeId: String, tagId: String): Boolean =
    resolveMomentsTap(typeId, tagId) != null

/**
 * The body to display for a chat notification. When real content is shown
 * ([showsRealContent] — the name_content_actions level), always the message itself: the Android
 * displayer stacks multiple per-conversation messages via MessagingStyle, so no count summary is
 * needed. At the redacted levels, collapse to a "$count new messages" summary once more than one
 * message has accumulated (there's no content to stack there anyway).
 */
internal fun notificationBody(
    displayBody: String,
    messageCount: Int,
    showsRealContent: Boolean,
): String =
    if (!showsRealContent && messageCount > 1) "$messageCount new messages" else displayBody

/**
 * Reserved offset so a conversation's group-summary id never collides with a
 * per-message notification id. Shared between the Android displayer (which posts
 * with these ids) and [conversationNotificationIds] (which cancels by them) so
 * the two can never drift.
 */
internal const val SUMMARY_ID_OFFSET = 100_000

/**
 * Derives the (per-message id, group-summary id) pair that a chat conversation's
 * notifications were posted under, so a single conversation can be cancelled
 * without touching any other. Must reproduce exactly what the display path used:
 *  - per-message id = [PushNotificationPayloadOptions.conversationNotificationId]
 *    (the raw, unmasked `typeId.hashCode()`), and
 *  - summary id = [SUMMARY_ID_OFFSET] + the masked hash reduced modulo
 *    (Int.MAX_VALUE - [SUMMARY_ID_OFFSET]) so the offset can't overflow Int (see
 *    RichNotificationDisplayer.postSummaryNotification, which delegates here).
 *
 * Degenerate case: if `conversationId.hashCode() == 0` the display path posts the
 * per-message notification under a random id instead, so a derived cancel would
 * miss it — astronomically rare for a UUID string, not special-cased.
 */
fun conversationNotificationIds(conversationId: String): Pair<Int, Int> {
    val messageId = conversationId.hashCode()
    // Reduce the (non-negative) masked hash into [0, Int.MAX_VALUE - SUMMARY_ID_OFFSET)
    // so the offset can never overflow Int into a negative summary id. Without the
    // modulo, `SUMMARY_ID_OFFSET + maskedHash` wrapped past Int.MAX_VALUE for the ~0.005%
    // of hashes within SUMMARY_ID_OFFSET of the top, producing a negative id.
    val maskedHash = (conversationId.hashCode() and 0x7FFFFFFF) % (Int.MAX_VALUE - SUMMARY_ID_OFFSET)
    val summaryId = SUMMARY_ID_OFFSET + maskedHash
    return messageId to summaryId
}
