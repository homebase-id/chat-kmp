package id.homebase.core.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject

@Composable
fun rememberEnterSendsMessage(): Boolean {
    val userPreferences: UserPreferences = koinInject()
    // Derived, not `by`: reading the whole PreferenceState would invalidate the caller's restart
    // scope whenever any unrelated preference changes.
    val prefState = userPreferences.preferenceState.collectAsStateWithLifecycle()
    return remember { derivedStateOf { prefState.value.enterSendsMessage } }.value
}
