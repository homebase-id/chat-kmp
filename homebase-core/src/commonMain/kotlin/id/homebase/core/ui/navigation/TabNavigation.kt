package id.homebase.core.ui.navigation

import androidx.navigation.FloatingWindow
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute

internal fun List<NavBackStackEntry>.currentTabRoot(tabs: List<Route>): NavBackStackEntry? =
    lastOrNull { entry -> tabs.any { entry.destination.hasRoute(it::class) } }

internal fun NavController.switchTab(tab: Route, tabs: List<Route>) {
    // Whatever was pushed over the tab being left (Settings, a pane, an overflow app) is not part
    // of that tab's saved stack: restoring it would bring it back the next time the tab is tapped.
    currentBackStack.value.currentTabRoot(tabs)?.let { popBackStack(it.destination.id, inclusive = false) }
    navigate(tab) {
        popUpTo(Route.ChatList) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

// An add-on without a bar item is not a tab: it opens on top of the current one, so Back returns there.
internal fun NavController.openApp(route: Route, tabs: List<Route>) {
    if (tabs.any { it::class == route::class }) {
        switchTab(route, tabs)
        return
    }
    while (currentBackStackEntry?.destination is FloatingWindow && popBackStack()) Unit
    navigate(route) { launchSingleTop = true }
}
