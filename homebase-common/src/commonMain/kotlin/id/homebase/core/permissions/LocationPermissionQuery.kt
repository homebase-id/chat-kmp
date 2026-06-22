package id.homebase.core.permissions

/**
 * Non-composable, off-composition read of whether the app currently holds
 * while-in-use location permission.
 *
 * [createPermissionsManager] is `@Composable` only because *requesting* a
 * permission needs a Compose-scoped `ActivityResult` launcher on Android.
 * Reading the current grant state needs no launcher, so this thin seam can be
 * provided from Koin (`platformModule()`) and consumed by plain singletons and
 * services such as [id.homebase.chat.services.livelocation.LiveShareReadiness].
 *
 * Resolving the full [PermissionsManager] from Koin is impossible — it has no
 * non-composable constructor — and attempting it (`get<PermissionsManager>()`)
 * is what crashed `ConversationListViewModel` at app start on every platform.
 */
fun interface LocationPermissionQuery {
    /** True if ACCESS_FINE or ACCESS_COARSE (while-in-use) location is granted. */
    suspend fun isGranted(): Boolean
}
