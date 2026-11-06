package id.homebase.core.ui.screens.filesync

import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class FilesyncUpTest {
    private fun crumb(name: String?) = FilesyncCrumb(Uuid.random(), name)
    private val home = crumb(null)
    private val photos = crumb("Photos")
    private val year = crumb("2024")
    private val frodo = OdinId("frodo.test")

    @Test
    fun yourOwnFoldersGoUpOne() {
        assertEquals(FilesyncUp(1, listOf(FilesyncUiAction.NavigateUp)), filesyncUp(listOf(home, photos, year), FilesyncSharingState()))
    }

    @Test
    fun atTheTopThereIsNowhereToGo() {
        assertNull(filesyncUp(listOf(home), FilesyncSharingState()))
    }

    @Test
    fun aSharersFoldersClimbToTheirTopThenHome() {
        val shared = crumb("Trip")
        val deeper = crumb("Day 1")
        // Crumbs are home, the sharer, then their folders.
        assertEquals(
            FilesyncUp(2, listOf(FilesyncUiAction.OpenSharedFolder(listOf(shared)))),
            filesyncUp(listOf(home, photos), FilesyncSharingState(sharer = frodo, sharerPath = listOf(shared, deeper))),
        )
        assertEquals(
            FilesyncUp(1, listOf(FilesyncUiAction.OpenSharedFolder(emptyList()))),
            filesyncUp(listOf(home, photos), FilesyncSharingState(sharer = frodo, sharerPath = listOf(shared))),
        )
        assertEquals(
            FilesyncUp(0, listOf(FilesyncUiAction.CloseShareView, FilesyncUiAction.NavigateToCrumb(0))),
            filesyncUp(listOf(home, photos), FilesyncSharingState(sharer = frodo)),
        )
    }

    @Test
    fun aRecipientsListClosesBackToWhereYouWere() {
        val sam = FilesyncAudience.Person(OdinId("sam.test"))
        assertEquals(
            FilesyncUp(2, listOf(FilesyncUiAction.CloseShareView)),
            filesyncUp(listOf(home, photos, year), FilesyncSharingState(recipient = sam)),
        )
        assertEquals(FilesyncUp(0, listOf(FilesyncUiAction.CloseShareView)), filesyncUp(listOf(home), FilesyncSharingState(recipient = sam)))
    }
}
