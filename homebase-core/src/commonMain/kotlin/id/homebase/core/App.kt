package id.homebase.core

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.settings.ThemeState
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.navigation.AppNavHost
import id.homebase.core.ui.theme.HomebaseTheme
import org.koin.compose.koinInject

/** Main application entry point. Sets up theme, and navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    onNavHostReady: suspend (NavController) -> Unit = {},
) {
    val navController = rememberNavController()
    val youAuthFlowManager: YouAuthFlowManager = koinInject()
    val userPreferences: UserPreferences = koinInject()

    val prefState by userPreferences.preferenceState.collectAsStateWithLifecycle()
    val isDarkTheme = if (prefState.theme == ThemeState.System) isSystemInDarkTheme() else if (prefState.theme == ThemeState.Dark) true else false

    HomebaseTheme(darkTheme = isDarkTheme) {
        AppNavHost(
            viewModel = koinInject(),
            navController = navController,
            youAuthFlowManager = youAuthFlowManager
        )
        LaunchedEffect(navController) { onNavHostReady(navController) }
    }
}
