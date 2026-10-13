package id.homebase.core.ui.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute

/** Short link names for the mini apps, e.g. the web app's `#filesync`. */
enum class AppLink(val slug: String, val route: Route) {
    Chat("chat", Route.ChatList),
    Feed("feed", Route.Feed),
    Moments("moments", Route.Moments),
    Vault("vault", Route.Vault),
    WebDrop("webdrop", Route.WebDrop),
    Filesync("filesync", Route.Filesync),
    Location("location", Route.Location),
    Contacts("contacts", Route.ContactBook),
    Email("email", Route.Email),
    Home("home", Route.Home),
    Settings("settings", Route.Settings);

    companion object {
        fun fromSlug(text: String): AppLink? {
            val slug = text.trimStart('#', '/').trimEnd('/')
            for (link in entries) {
                if (link.slug.equals(slug, ignoreCase = true)) return link
            }
            return null
        }

        /** The app the user is in: the top-most app root on the stack, so its sub-screens keep its link. */
        fun forBackStack(stack: List<NavBackStackEntry>): AppLink? {
            for (i in stack.indices.reversed()) {
                for (link in entries) {
                    if (stack[i].destination.hasRoute(link.route::class)) return link
                }
            }
            return null
        }
    }
}
