package id.homebase.core.ui.screens.email.auth

import androidx.compose.runtime.Composable
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import id.homebase.core.email.EmailPreferences
import id.homebase.core.ui.navigation.Route
import id.homebase.core.ui.screens.auth.BiometricFlowTracker
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
    BiometricFlowTracker(
        navController = navController,
        anywhereInBackStack = false,
        inFlow = { it.hasRoute(Route.EmailSecrets::class) || it.hasRoute(Route.EmailThunderbirdSetup::class) },
        onFlowActive = emailPreferences::setEmailScreenActive,
        onStop = { if (emailPreferences.biometricsEnabled.value) emailPreferences.recordAppBackgrounded() },
        onStart = { if (emailPreferences.biometricsEnabled.value) emailPreferences.recordAppForegrounded() },
    )
}
