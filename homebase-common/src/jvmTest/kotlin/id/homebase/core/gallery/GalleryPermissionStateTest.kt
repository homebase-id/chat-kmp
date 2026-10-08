package id.homebase.core.gallery

import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.PermissionsManager
import kotlin.test.Test
import kotlin.test.assertEquals

class GalleryPermissionStateTest {
    private val manager =
        object : PermissionsManager {
            override fun askPermission(permission: PermissionType) {}

            override suspend fun isPermissionGranted(permission: PermissionType) = false

            override fun launchSettings() {}
        }

    private fun canSelectMore(full: Boolean, partial: Boolean) =
        GalleryPermissionState(full, partial, false, false, manager).canSelectMorePhotos

    @Test
    fun partialOnlyCanSelectMore() = assertEquals(true, canSelectMore(full = false, partial = true))

    @Test
    fun fullAccessCannotSelectMore() = assertEquals(false, canSelectMore(full = true, partial = false))

    @Test
    fun noAccessCannotSelectMore() = assertEquals(false, canSelectMore(full = false, partial = false))
}
