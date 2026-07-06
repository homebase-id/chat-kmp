package id.homebase.core.ui.screens.vault.auth

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
import id.homebase.core.ui.navigation.Route
import id.homebase.core.vault.VaultPreferences
import org.koin.compose.koinInject

/**
 * Owns the Vault biometric-session signals for the whole Vault flow (grid, note editor, …).
 * Each sub-screen is its own nav route, so [VaultBiometricGate] is disposed when one is pushed
 * on top; owning these here — off the back stack, on the Activity lifecycle — keeps them
 * correct across every sub-screen. Call once from the app nav host.
 *
 * - **Idle suppression:** `isVaultScreenActive` is true while anywhere in the Vault, which
 *   suppresses the 5-minute idle auto-lock. A genuine app-background still re-locks via (B).
 * - **Background detection:** records the app-background on the **Activity** `ON_STOP` (which
 *   fires only when the whole app leaves — NOT when navigating between Vault screens, which
 *   only stops a `NavBackStackEntry`). This is why background recording lives here and not in
 *   the gate, whose observer is bound to the grid's entry and mis-fired on navigation. A
 *   presented picker also stops the Activity but isn't "leaving", so it's skipped via
 *   `isPickerActive`.
 */
@Composable
fun VaultSessionTracker(
    navController: NavHostController,
    vaultPreferences: VaultPreferences = koinInject(),
) {
    val backStack by navController.currentBackStack.collectAsStateWithLifecycle()
    val inVaultFlow = backStack.any { it.destination.hasRoute(Route.Vault::class) }
    LaunchedEffect(inVaultFlow) {
        vaultPreferences.setVaultScreenActive(inVaultFlow)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, inVaultFlow) {
        if (!inVaultFlow) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP &&
                vaultPreferences.biometricsEnabled.value &&
                !vaultPreferences.isPickerActive
            ) {
                vaultPreferences.recordAppBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
