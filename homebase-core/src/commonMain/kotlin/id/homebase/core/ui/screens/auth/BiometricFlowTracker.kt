package id.homebase.core.ui.screens.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

/**
 * Feeds a biometric session from the nav back stack and the Activity lifecycle. [inFlow] decides
 * whether a destination belongs to the gated flow; [anywhereInBackStack] widens the check from the
 * current destination to the whole back stack.
 */
@Composable
fun BiometricFlowTracker(
    navController: NavHostController,
    anywhereInBackStack: Boolean,
    inFlow: (NavDestination) -> Boolean,
    onFlowActive: (Boolean) -> Unit,
    onStop: () -> Unit,
    onStart: () -> Unit = {},
) {
    val inGatedFlow = if (anywhereInBackStack) {
        val backStack by navController.currentBackStack.collectAsStateWithLifecycle()
        backStack.any { inFlow(it.destination) }
    } else {
        val current by navController.currentBackStackEntryAsState()
        current?.destination?.let(inFlow) == true
    }
    LaunchedEffect(inGatedFlow) { onFlowActive(inGatedFlow) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, inGatedFlow) {
        if (!inGatedFlow) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> onStop()
                Lifecycle.Event.ON_START -> onStart()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
