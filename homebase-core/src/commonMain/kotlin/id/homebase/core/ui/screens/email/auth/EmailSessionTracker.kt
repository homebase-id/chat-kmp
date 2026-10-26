package id.homebase.core.ui.screens.email.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import id.homebase.core.email.EmailPreferences
import id.homebase.core.ui.navigation.Route
import org.koin.compose.koinInject

/**
 * Owns the email biometric-session signals for the whole email flow. Staying inside the flow
 * suppresses the idle lock; leaving it (or backgrounding the app) starts the 5-minute clock.
 */
@Composable
fun EmailSessionTracker(
    navController: NavHostController,
    emailPreferences: EmailPreferences = koinInject(),
) {
    val backStack by navController.currentBackStack.collectAsStateWithLifecycle()
    val inEmailFlow = backStack.any {
        val d = it.destination
        d.hasRoute(Route.Email::class) ||
            d.hasRoute(Route.EmailSecrets::class) ||
            d.hasRoute(Route.EmailThunderbirdSetup::class)
    }
    LaunchedEffect(inEmailFlow) {
        emailPreferences.setEmailScreenActive(inEmailFlow)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, inEmailFlow) {
        if (!inEmailFlow) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && emailPreferences.biometricsEnabled.value) {
                emailPreferences.recordAppBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
