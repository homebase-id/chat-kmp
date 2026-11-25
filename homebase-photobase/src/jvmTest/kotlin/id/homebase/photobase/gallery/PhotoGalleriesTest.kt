package id.homebase.photobase.gallery

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.sync.database.DatabaseManager
import kotlin.test.AfterTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoGalleriesTest {
    private val scopes = mutableMapOf<PhotoGallery, CoroutineScope>()
    private val db = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })

    @AfterTest
    fun close() = db.close()

    private fun CoroutineScope.galleries() = PhotoGalleries(this, keptAlbums = 2) { gallery, scope ->
        scopes[gallery] = scope
        val store = PhotoGalleryStore(db, { Uuid.random() }, gallery)
        OpenGallery(store, PhotoDayIndex({ emptyList() }, { 0 }, { 0 }, { "" }, MutableSharedFlow(), scope))
    }

    @Test
    fun aGalleryIsMadeOnceAndAnAlbumGoesWhenTwoOthersWereOpenedSince() = runTest(UnconfinedTestDispatcher()) {
        val galleries = backgroundScope.galleries()
        val (first, second, third) = List(3) { PhotoGallery.Album(Uuid.random()) }

        val opened = galleries.of(first)
        assertSame(opened, galleries.of(first))
        galleries.of(PhotoGallery.Favorites)
        galleries.of(second)
        galleries.of(third)

        assertFalse(scopes.getValue(first).isActive, "its day index stops following the drive")
        assertTrue(scopes.getValue(second).isActive)
        assertTrue(scopes.getValue(PhotoGallery.Favorites).isActive, "only albums go")
        assertEquals(false, opened === galleries.of(first), "made again when opened again")
    }

    @Test
    fun anAlbumOnScreenStays() = runTest(UnconfinedTestDispatcher()) {
        val galleries = backgroundScope.galleries()
        val (shown, second, third) = List(3) { PhotoGallery.Album(Uuid.random()) }
        val screen = launch { galleries.of(shown).days.days.collect { } }

        galleries.of(second)
        galleries.of(third)

        assertTrue(scopes.getValue(shown).isActive)
        assertFalse(scopes.getValue(second).isActive, "the next least recent goes instead")
        screen.cancel()
    }

    @Test
    fun theAlbumJustOpenedStaysWhileTheOthersAreStillWatched() = runTest(UnconfinedTestDispatcher()) {
        val galleries = backgroundScope.galleries()
        val (first, second, opened) = List(3) { PhotoGallery.Album(Uuid.random()) }
        val screens = listOf(first, second).map { launch { galleries.of(it).days.days.collect { } } }

        galleries.of(opened)

        assertTrue(scopes.getValue(opened).isActive, "its screen hasn't started watching yet")
        screens.forEach { it.cancel() }
    }
}
