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
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.App
import id.homebase.core.notifications.NotificationIntentDecision
import id.homebase.core.notifications.NotificationService
import id.homebase.core.notifications.RichNotificationDisplayer
import id.homebase.core.notifications.decideNotificationIntent
import id.homebase.core.notifications.isReplayedFromHistory
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        ActivityProvider.initialize(this)

        // Notify KMPNotifier of activity create
        NotifierManager.onCreateOrOnNewIntent(intent)

        // Handle custom notification tap intent
        handleNotificationIntent(intent)

        // Initialize FileKit
        FileKit.manualFileKitCoreInitialization(this)
        FileKit.init(this)

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
                Logger.i(tag = "MainActivity") { "Deep link: navigating to conversation $conversationId" }
                notificationService.navigateToConversation(conversationId)
                // Clear the deep link so it's not re-processed on config changes
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
                notificationService.handleNotificationClicked(decision.payload)
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
