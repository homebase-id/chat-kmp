package id.homebase.feed

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import com.mmk.kmpnotifier.extensions.onCreateOrOnNewIntent
import com.mmk.kmpnotifier.notification.NotifierManager
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.App
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import io.github.vinceglb.filekit.manualFileKitCoreInitialization
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : AppCompatActivity() {

    val youAuthFlowManager: YouAuthFlowManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        // Notify KMPNotifier of activity create
        NotifierManager.onCreateOrOnNewIntent(intent)

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
    }

    override fun onResume() {
        super.onResume()

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
            val callbackURL = data.toString()
            lifecycleScope.launch { youAuthFlowManager.handleCallback(callbackURL) }
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
