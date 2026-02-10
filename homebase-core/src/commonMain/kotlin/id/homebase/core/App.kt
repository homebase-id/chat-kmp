package id.homebase.core

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.di.allModules
import id.homebase.core.notifications.NotificationService
import id.homebase.core.ui.navigation.AppNavHost
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.coroutines.launch
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject

/** Main application entry point. Sets up Koin DI, theme, and navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KoinApp(
    onNavHostReady: suspend (NavController) -> Unit = {},
) {
    KoinApplication(application = { modules(allModules) }) { App(onNavHostReady = onNavHostReady) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    onNavHostReady: suspend (NavController) -> Unit = {},
) {
    val navController = rememberNavController()
    val youAuthFlowManager: YouAuthFlowManager = koinInject()
    val notificationService: NotificationService = koinInject()
    val coordinator: AuthConnectionCoordinator = koinInject()

    HomebaseTheme {
        LaunchedEffect(Unit) {
            launch { notificationService.startListening() }
            launch { youAuthFlowManager.authState.collect { coordinator.onAuthStateChanged(it) } }
        }

        AppNavHost(navController = navController, youAuthFlowManager = youAuthFlowManager)
        LaunchedEffect(navController) { onNavHostReady(navController) }
    }
}
