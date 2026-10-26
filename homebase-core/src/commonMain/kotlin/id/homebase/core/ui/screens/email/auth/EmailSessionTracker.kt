package id.homebase.core.ui.screens.email.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import id.homebase.core.email.EmailPreferences
import id.homebase.core.ui.navigation.Route
import org.koin.compose.koinInject

/**
 * Owns the email biometric-session signals. Only the gated screens count as active: the Email
 * root is ungated, so letting it suppress the idle lock would revive a session that aged out
 * while the user was elsewhere in the app.
 */
@Composable
fun EmailSessionTracker(
    navController: NavHostController,
    emailPreferences: EmailPreferences = koinInject(),
) {
    val current by navController.currentBackStackEntryAsState()
    val inEmailFlow = current?.destination?.let {
        it.hasRoute(Route.EmailSecrets::class) || it.hasRoute(Route.EmailThunderbirdSetup::class)
    } == true
    LaunchedEffect(inEmailFlow) {
        emailPreferences.setEmailScreenActive(inEmailFlow)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, inEmailFlow) {
        if (!inEmailFlow) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (!emailPreferences.biometricsEnabled.value) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_STOP -> emailPreferences.recordAppBackgrounded()
                Lifecycle.Event.ON_START -> emailPreferences.recordAppForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
