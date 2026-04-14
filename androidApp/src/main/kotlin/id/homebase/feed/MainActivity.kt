package id.homebase.feed

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import com.mmk.kmpnotifier.extensions.onCreateOrOnNewIntent
import com.mmk.kmpnotifier.notification.NotifierManager
import id.homebase.api.ActivityProvider
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.App
import id.homebase.core.notifications.NotificationService
import id.homebase.core.notifications.RichNotificationDisplayer
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import io.github.vinceglb.filekit.manualFileKitCoreInitialization
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

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

        setContent { App() }
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
        }
    }

    /**
     * Handles intents from custom notification taps (created by RichNotificationDisplayer).
     * Extracts the payload data from intent extras and routes via NotificationService.
     */
    private fun handleNotificationIntent(intent: Intent) {
        // Check for our always-present marker extra (works for all notification types,
        // not just chat notifications that carry a conversationId)
        if (!intent.getBooleanExtra(RichNotificationDisplayer.EXTRA_NOTIFICATION_TAP, false)) return

        val conversationId = intent.getStringExtra(
            RichNotificationDisplayer.EXTRA_NOTIFICATION_CONVERSATION_ID
        )
        Logger.i(tag = "MainActivity") {
            "Notification intent detected (conversation: $conversationId)"
        }

        // Build PayloadData map from intent extras (RichNotificationDisplayer puts all
        // original notification payload entries as extras)
        val extras = intent.extras ?: return
        val payloadData = mutableMapOf<String, Any>()
        for (key in extras.keySet()) {
            @Suppress("DEPRECATION")
            extras.get(key)?.let { payloadData[key] = it }
        }

        notificationService.handleNotificationClicked(payloadData)

        // Clear the notification extras so we don't re-handle on config change
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
