package id.homebase.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import id.homebase.core.di.allModules
import id.homebase.core.ui.navigation.AppNavHost
import id.homebase.core.ui.theme.HomebaseTheme
import org.koin.compose.KoinApplication

/** Main application entry point. Sets up Koin DI, theme, and navigation. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun KoinApp(
    onNavHostReady: suspend (NavController) -> Unit = {},
) {
    KoinApplication(application = { modules(allModules) }) {
        App(
            onNavHostReady = onNavHostReady
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun App(
    onNavHostReady: suspend (NavController) -> Unit = {},
) {
    val navController = rememberNavController()
    HomebaseTheme {
        AppNavHost(
            navController = navController,
        )
        LaunchedEffect(navController) {
            onNavHostReady(navController)
        }
    }

}
