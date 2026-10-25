package id.homebase.core

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import id.homebase.chat.calling.CallChrome
import id.homebase.chat.calling.CallHost
import id.homebase.chat.calling.CallWindowModel
import id.homebase.chat.widget.GhostProbe
import id.homebase.core.diagnostics.DiagnosticsCrashTrigger
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.logging.StartupLogger
import id.homebase.core.settings.ThemeState
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.navigation.AppNavHost
import id.homebase.core.ui.navigation.AppViewModel
import id.homebase.core.ui.navigation.Route
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.session.IdentityScopeProvider
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Main application entry point. Sets up theme, and navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    onNavHostReady: suspend (NavController) -> Unit = {},
    onNavigatorReady: ((Route) -> Unit) -> Unit = {},
    topInset: Dp = 0.dp,
    hostCallOverlay: Boolean = true,
) {
    remember { StartupLogger.checkpoint("App() first composition") }
    val navController = rememberNavController()
    val youAuthFlowManager: YouAuthFlowManager = koinInject()
    val userPreferences: UserPreferences = koinInject()
    val crashTrigger: DiagnosticsCrashTrigger = koinInject()
    LaunchedEffect(crashTrigger) { GhostProbe.enabled = crashTrigger.enabled }
    remember { StartupLogger.checkpoint("App() Koin injections done") }

    val prefState by userPreferences.preferenceState.collectAsStateWithLifecycle()
    val isDarkTheme = if (prefState.theme == ThemeState.System) isSystemInDarkTheme() else if (prefState.theme == ThemeState.Dark) true else false

    HomebaseTheme(
        darkTheme = isDarkTheme,
        followsSystemTheme = prefState.theme == ThemeState.System,
    ) {
        // Resolved OUTSIDE IdentityScopeProvider deliberately. AppViewModel is app-lifetime
        // (it owns credential watching and the auth coordinator, and exists before login), and
        // Koin does not include the scope in a ViewModel's store key — so resolving it inside a
        // scope that comes and goes buys nothing and risks the duplicate-instance problem the
        // comment below describes.
        val appViewModel = koinViewModel<AppViewModel>()
        // Everything below resolves from the open identity session, so per-identity services and
        // the ViewModels that consume them are destroyed at logout rather than reset.
        CallChrome(
            callWindow = if (hostCallOverlay) null else koinInject<CallWindowModel>(),
            topInset = topInset,
            callHost = { content -> CallHost(viewModel = koinViewModel(), modifier = Modifier.fillMaxSize(), content = content) },
        ) { contentTopInset ->
            IdentityScopeProvider {
                AppNavHost(
                    // koinViewModel(), not koinInject(): the factory resolved outside a ViewModelStore leaks
                    // orphans whose collectors steal events from the single-consumer navigation channel.
                    viewModel = appViewModel,
                    navController = navController,
                    youAuthFlowManager = youAuthFlowManager,
                    topInset = contentTopInset,
                )
            }
        }
        LaunchedEffect(navController) {
            onNavigatorReady { route -> navController.navigate(route) { launchSingleTop = true } }
            onNavHostReady(navController)
        }
    }
}
