package id.homebase.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.App
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's intent
        handleIntent(intent)
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
            // This is handled inside YouAuthFlowManager.onAppResumed()
            // Note: We can't easily get YouAuthFlowManager from Koin here,
            // so the cancellation is handled in LoginViewModel instead
        }
    }

    private fun handleIntent(intent: Intent) {
        val data = intent.data
        if (data != null && data.scheme == "youauth") {
            val callbackURL = data.toString()
            lifecycleScope.launch {
                YouAuthFlowManager.handleCallback(callbackURL)
            }
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
