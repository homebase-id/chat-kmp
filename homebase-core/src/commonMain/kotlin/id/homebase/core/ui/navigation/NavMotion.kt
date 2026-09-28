@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.core.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute

internal expect object PlatformNavMotion {
    fun push(scheme: MotionScheme): EnterTransition
    fun pushExit(scheme: MotionScheme): ExitTransition
    fun pop(scheme: MotionScheme): EnterTransition
    fun popExit(scheme: MotionScheme): ExitTransition
}

internal enum class RouteMotion { Push, Tab, Modal, Fade, Redirect }

internal fun NavDestination?.isTopLevelRoute(): Boolean {
    return this?.hasRoute(Route.ChatList::class) == true ||
            this?.hasRoute(Route.Feed::class) == true ||
            this?.hasRoute(Route.Moments::class) == true ||
            this?.hasRoute(Route.Home::class) == true ||
            this?.hasRoute(Route.Vault::class) == true ||
            this?.hasRoute(Route.Email::class) == true ||
            this?.hasRoute(Route.Location::class) == true ||
            this?.hasRoute(Route.ContactBook::class) == true
}

internal fun NavDestination.routeMotion(): RouteMotion = when {
    isTopLevelRoute() -> RouteMotion.Tab
    hasRoute(Route.ProfileCard::class) ||
            hasRoute(Route.VaultNoteEditor::class) ||
            hasRoute(Route.Crop::class) ||
            hasRoute(Route.Draw::class) -> RouteMotion.Modal
    // Splash and login are not a step in the hierarchy; the card editor redraws the card it opens from.
    hasRoute(Route.AppLoading::class) ||
            hasRoute(Route.Login::class) ||
            hasRoute(Route.ProfileCardEditor::class) -> RouteMotion.Fade
    hasRoute(Route.VaultEntryDetail::class) -> RouteMotion.Redirect
    else -> RouteMotion.Push
}

internal fun fadeThroughIn(scheme: MotionScheme): EnterTransition =
    fadeIn(scheme.defaultEffectsSpec()) + scaleIn(scheme.defaultSpatialSpec(), initialScale = 0.92f)

internal fun fadeThroughOut(scheme: MotionScheme): ExitTransition = fadeOut(scheme.fastEffectsSpec())

// The card viewer/editor render inside CardExpressiveTheme (MotionScheme.expressive()); match their
// route transition to that instead of the app's ambient (standard) scheme.
private fun NavDestination?.isCardRoute(): Boolean =
    this?.hasRoute(Route.ProfileCard::class) == true || this?.hasRoute(Route.ProfileCardEditor::class) == true

private fun cardAwareScheme(scheme: MotionScheme, destination: NavDestination?): MotionScheme =
    if (destination.isCardRoute()) MotionScheme.expressive() else scheme

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navEnter(scheme: MotionScheme): EnterTransition {
    val effective = cardAwareScheme(scheme, targetState.destination)
    return when (targetState.destination.routeMotion()) {
        RouteMotion.Push -> PlatformNavMotion.push(effective)
        RouteMotion.Tab -> fadeThroughIn(effective)
        // Effects springs don't overshoot; a spatial one would pull a full-height slide past its edge.
        RouteMotion.Modal -> slideInVertically(effective.slowEffectsSpec()) { it } + fadeIn(effective.defaultEffectsSpec())
        RouteMotion.Fade -> fadeIn(effective.defaultEffectsSpec())
        RouteMotion.Redirect -> EnterTransition.None
    }
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navExit(scheme: MotionScheme): ExitTransition {
    val effective = cardAwareScheme(scheme, targetState.destination)
    return when (targetState.destination.routeMotion()) {
        RouteMotion.Push -> PlatformNavMotion.pushExit(effective)
        RouteMotion.Tab -> fadeThroughOut(effective)
        RouteMotion.Modal -> ExitTransition.KeepUntilTransitionsFinished
        RouteMotion.Fade -> fadeOut(effective.fastEffectsSpec())
        RouteMotion.Redirect -> ExitTransition.KeepUntilTransitionsFinished
    }
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopEnter(scheme: MotionScheme): EnterTransition {
    val effective = cardAwareScheme(scheme, initialState.destination)
    return when (initialState.destination.routeMotion()) {
        RouteMotion.Push -> PlatformNavMotion.pop(effective)
        RouteMotion.Tab -> fadeThroughIn(effective)
        RouteMotion.Modal -> EnterTransition.None
        RouteMotion.Fade -> fadeIn(effective.defaultEffectsSpec())
        RouteMotion.Redirect -> EnterTransition.None
    }
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopExit(scheme: MotionScheme): ExitTransition {
    val effective = cardAwareScheme(scheme, initialState.destination)
    return when (initialState.destination.routeMotion()) {
        RouteMotion.Push -> PlatformNavMotion.popExit(effective)
        RouteMotion.Tab -> fadeThroughOut(effective)
        RouteMotion.Modal ->
            slideOutVertically(effective.defaultEffectsSpec()) { it } + fadeOut(effective.slowEffectsSpec())
        RouteMotion.Fade -> fadeOut(effective.fastEffectsSpec())
        RouteMotion.Redirect -> ExitTransition.None
    }
}
