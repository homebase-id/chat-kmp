package id.homebase.core

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.remember
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.logging.StartupLogger
import id.homebase.core.settings.ThemeState
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.navigation.AppNavHost
import id.homebase.core.ui.navigation.AppViewModel
import id.homebase.core.ui.navigation.Route
import id.homebase.core.ui.theme.HomebaseTheme
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Main application entry point. Sets up theme, and navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    onNavHostReady: suspend (NavController) -> Unit = {},
    onNavigatorReady: ((Route) -> Unit) -> Unit = {},
) {
    remember { StartupLogger.checkpoint("App() first composition") }
    val navController = rememberNavController()
    val youAuthFlowManager: YouAuthFlowManager = koinInject()
    val userPreferences: UserPreferences = koinInject()
    remember { StartupLogger.checkpoint("App() Koin injections done") }

    val prefState by userPreferences.preferenceState.collectAsStateWithLifecycle()
    val isDarkTheme = if (prefState.theme == ThemeState.System) isSystemInDarkTheme() else if (prefState.theme == ThemeState.Dark) true else false

    HomebaseTheme(
        darkTheme = isDarkTheme,
        followsSystemTheme = prefState.theme == ThemeState.System,
    ) {
        AppNavHost(
            // koinViewModel(), NOT koinInject(). AppViewModel is registered with
            // viewModelOf(), which is a *factory* definition — koinInject() resolves
            // it outside any ViewModelStore, so every Activity recreation minted a
            // fresh AppViewModel whose onCleared() was never called. Each orphan kept
            // its collectNotificationEvents() coroutine alive on
            // NotificationService.navigationEvents, which is a single-consumer
            // Channel.receiveAsFlow(): N live collectors round-robin the events, and
            // an orphan that wins a turn forwards into its own channel that nothing
            // reads. Result — notification taps and share deep links silently went
            // nowhere (build 1788 log: three consecutive events "forwarded" by
            // AppViewModel with no matching AppNavHost receipt, after four Activity
            // onCreates in one process).
            viewModel = koinViewModel<AppViewModel>(),
            navController = navController,
            youAuthFlowManager = youAuthFlowManager
        )
        LaunchedEffect(navController) {
            onNavigatorReady { route -> navController.navigate(route) { launchSingleTop = true } }
            onNavHostReady(navController)
        }
    }
}
