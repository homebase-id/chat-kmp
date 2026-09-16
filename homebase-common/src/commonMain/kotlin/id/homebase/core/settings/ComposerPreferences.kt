package id.homebase.core.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject

/**
 * The composer's Enter chord, read live so a change in the settings pane reaches a composer that
 * is already on screen. Screens pass the value into the composer; the widgets take a plain flag.
 */
@Composable
fun rememberEnterSendsMessage(): Boolean {
    val userPreferences: UserPreferences = koinInject()
    val preferences by userPreferences.preferenceState.collectAsStateWithLifecycle()
    return preferences.enterSendsMessage
}
