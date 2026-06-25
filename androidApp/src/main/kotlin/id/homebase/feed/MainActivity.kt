package id.homebase.feed

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import com.mmk.kmpnotifier.extensions.onCreateOrOnNewIntent
import com.mmk.kmpnotifier.notification.NotifierManager
import id.homebase.api.ActivityProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.App
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.notifications.NotificationEntry
import id.homebase.core.notifications.NotificationIntentDecision
import id.homebase.core.notifications.NotificationNavigationEvent
import id.homebase.core.notifications.NotificationService
import id.homebase.core.notifications.RichNotificationDisplayer
import id.homebase.core.logging.StartupLogger
import id.homebase.core.notifications.decideNotificationIntent
import id.homebase.core.notifications.isReplayedFromHistory
import id.homebase.feed.crash.NativeCrashRecovery
import id.homebase.feed.share.ShareShortcutPublisher
import id.homebase.core.settings.ThemeState
import id.homebase.core.settings.UserPreferences
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import io.github.vinceglb.filekit.manualFileKitCoreInitialization
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject

class MainActivity : AppCompatActivity() {

    val youAuthFlowManager: YouAuthFlowManager by inject()
    private val notificationService: NotificationService by inject()
    private val notificationEntry: NotificationEntry by inject()
    private val eventBus: EventBus by inject()
    private val authConnectionCoordinator: AuthConnectionCoordinator by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // If the previous run died in a *native* crash (an NDK signal — e.g. a SQLCipher
        // SIGSEGV — that the JVM GlobalCrashHandler can never see), surface the recovery
        // screen now instead of starting normally. Returns false on a clean previous run.
        if (NativeCrashRecovery.checkAndMaybeLaunch(this)) {
            finish()
            return
        }

        // The gap from MainApplication's "onCreate end" to here is process-idle /
        // background→foreground (a background-woken process sits here until the user
        // opens it). The gap from here to "App() first composition" is the real
        // user-perceived cold start. See StartupLogger.
        StartupLogger.checkpoint("activity onCreate start")

        // Promote AuthCC out of headless mode — this is the first reliable signal
        // that the user is looking at the UI (FCM-cold-wake doesn't reach here).
        // Idempotent; safe across Activity recreation. See AuthCC.promoteToForeground.
        authConnectionCoordinator.promoteToForeground()

        handleIntent(intent)

        ActivityProvider.initialize(this)

        // Notify KMPNotifier of activity create
        NotifierManager.onCreateOrOnNewIntent(intent)

        // Handle custom notification tap intent
        handleNotificationIntent(intent)

        // Initialize FileKit
        FileKit.manualFileKitCoreInitialization(this)
        FileKit.init(this)

        // Marked here (not inside the composable body, which re-fires per recomposition):
        // setContent → "App() first composition" is the first-frame / composition cost.
        StartupLogger.checkpoint("setContent")
        setContent {
            val userPreferences = koinInject<UserPreferences>()
            val prefState by userPreferences.preferenceState.collectAsStateWithLifecycle()
            val isDarkTheme = when (prefState.theme) {
                ThemeState.System -> isSystemInDarkTheme()
                ThemeState.Dark -> true
                ThemeState.Light -> false
            }

            DisposableEffect(isDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                )
                onDispose {}
            }

            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's intent
        handleIntent(intent)

        // Notify KMPNotifier of new intent
        NotifierManager.onCreateOrOnNewIntent(intent)

        // Handle custom notification tap intent
        handleNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ActivityProvider.initialize(this)

        // Check if browser was closed without completing auth
        // This is called when user returns from Custom Tab without completing auth
        // We use a small delay to allow the callback to be processed first if it arrives
        lifecycleScope.launch {
            // Small delay to allow callback to be processed first
            kotlinx.coroutines.delay(300)

            // If no callback arrived and we're still authenticating, the user likely cancelled
            youAuthFlowManager.onAppResumed()
        }
    }

    private fun handleIntent(intent: Intent) {
        val data = intent.data
        if (data != null && data.scheme == "homebase-fchat") {
            // Deep link: homebase-fchat://conversation/{conversationId}
            if (data.host == "conversation" && data.pathSegments.isNotEmpty()) {
                val conversationId = data.pathSegments.first()
                val fromShareShortcut = intent.getBooleanExtra(
                    ShareShortcutPublisher.EXTRA_FROM_SHARE_SHORTCUT, false
                )
                val source = if (fromShareShortcut) {
                    NotificationNavigationEvent.OpenConversation.Source.ShareIntent
                } else {
                    NotificationNavigationEvent.OpenConversation.Source.NotificationTap
                }
                Logger.i(tag = "MainActivity") {
                    "Deep link: navigating to conversation $conversationId source=$source"
                }
                notificationService.navigateToConversation(conversationId, source = source)
                // Clear the deep link so it's not re-processed on config changes
                intent.data = null
                return
            }

            // Share-into-"New Moment" deep link: homebase-fchat://moment-compose
            // The share flow already seeded MomentCreateFlowState with the chosen
            // media; this only steers the nav stack into the composer.
            if (data.host == "moment-compose") {
                Logger.i(tag = "MainActivity") { "Deep link: navigating to moment compose" }
                notificationService.navigateToMomentCompose()
                intent.data = null
                return
            }

            // Owner-console "Extend Permissions" return URL.
            // Path: homebase-fchat://permission-callback?status=[canceled|...]
            if (data.host == "permission-callback") {
                val status = data.getQueryParameter("status")
                val canceled = status.equals("canceled", ignoreCase = true) ||
                    status.equals("cancelled", ignoreCase = true)
                Logger.i(tag = "MainActivity") {
                    "Permission-extend deep link (status=$status canceled=$canceled)"
                }
                lifecycleScope.launch {
                    eventBus.emit(
                        if (canceled) BackendEvent.PermissionsExtensionCanceled
                        else BackendEvent.PermissionsExtensionReturned
                    )
                }
                intent.data = null
                return
            }

            // Owner-console "Data Upgrade" return URL.
            // Path: homebase-fchat://data-upgrade-callback
            if (data.host == "data-upgrade-callback") {
                Logger.i(tag = "MainActivity") { "Data-upgrade deep link return" }
                lifecycleScope.launch {
                    eventBus.emit(BackendEvent.DataUpgradeReturned)
                }
                intent.data = null
                return
            }

            // Auth callback
            val callbackURL = data.toString()
            lifecycleScope.launch { youAuthFlowManager.handleCallback(callbackURL) }
            // Clear the deep link so it's not re-processed on config changes / onNewIntent
            intent.data = null
        }
    }

    /**
     * Handles intents from custom notification taps (created by RichNotificationDisplayer).
     * Extracts the payload data from intent extras and routes via NotificationService.
     */
    private fun handleNotificationIntent(intent: Intent) {
        val isMarked = intent.getBooleanExtra(RichNotificationDisplayer.EXTRA_NOTIFICATION_TAP, false)
        val payload = intent.extras?.let { extras ->
            buildMap<String, Any> {
                for (key in extras.keySet()) {
                    @Suppress("DEPRECATION") extras.get(key)?.let { put(key, it) }
                }
            }
        } ?: emptyMap()

        when (val decision = decideNotificationIntent(intent.flags, isMarked, payload)) {
            NotificationIntentDecision.Skip -> {
                // The replay case — Android resumed the activity from recents and
                // handed back the original launching Intent (with stale extras).
                // We log it specifically because the symptom from homebase.log
                // 2026-05-01 08:05:49 was a stale tap auto-navigating the user.
                if (isReplayedFromHistory(intent.flags) && isMarked) {
                    Logger.i(tag = "MainActivity") {
                        "Skipping replayed notification intent (FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY set)"
                    }
                }
            }
            is NotificationIntentDecision.Process -> {
                val conversationId = intent.getStringExtra(
                    RichNotificationDisplayer.EXTRA_NOTIFICATION_CONVERSATION_ID
                )
                Logger.i(tag = "MainActivity") {
                    "Notification intent detected (conversation: $conversationId)"
                }
                notificationEntry.onNotificationTappedAsync(decision.payload)
            }
        }

        // Clear the notification extras so we don't re-handle on config change
        // within this same activity instance (process death + recents resume is
        // covered by the FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY check above).
        intent.removeExtra(RichNotificationDisplayer.EXTRA_NOTIFICATION_TAP)
        intent.removeExtra(RichNotificationDisplayer.EXTRA_NOTIFICATION_CONVERSATION_ID)
        intent.removeExtra("data")
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
