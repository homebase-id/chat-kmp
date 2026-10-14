package id.homebase.core.ui.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute

/** Link names for the mini apps, e.g. the web app's `#filesync`. Each slug is its route's `@SerialName`. */
enum class AppLink(val slug: String, val route: Route) {
    Chat("conversation", Route.ChatList),
    Feed("feed", Route.Feed),
    Moments("moments", Route.Moments),
    Vault("vault", Route.Vault),
    WebDrop("webdrop", Route.WebDrop),
    Filesync("filesync", Route.Filesync),
    Location("location", Route.Location),
    Contacts("contactbook", Route.ContactBook),
    Email("email", Route.Email),
    Home("home", Route.Home),
    Settings("settings", Route.Settings);

    companion object {
        private val routes = entries.map { it.route }

        fun fromSlug(text: String): AppLink? {
            val slug = text.trimStart('#', '/').trimEnd('/')
            for (link in entries) {
                if (link.slug.equals(slug, ignoreCase = true)) return link
            }
            return null
        }

        /** The app the user is in, so its sub-screens keep its link. */
        fun forBackStack(stack: List<NavBackStackEntry>): AppLink? {
            val root = stack.currentTabRoot(routes) ?: return null
            for (link in entries) {
                if (root.destination.hasRoute(link.route::class)) return link
            }
            return null
        }
    }
}
