package id.homebase.chat.entephotos

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.config.EntePhotosDrive
import id.homebase.core.config.getEntePhotosPermissionExtensionConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class EntePhotosViewModelTest {

    private fun item(name: String, video: Boolean = false, size: Long = 1_000) = EntePhoto(
        fileId = Uuid.random(),
        uniqueId = Uuid.random(),
        title = name,
        takenMicros = null,
        original = PayloadDescriptor(key = "original", contentType = if (video) "video/mp4" else "image/jpeg", bytesWritten = size),
        payloadKey = KeyHeader(ByteArray(16), SecureByteArray(ByteArray(16))),
        isVideo = video,
    )

    private class FakeLibrary(
        val library: List<EntePhoto>,
        val albums: List<EnteAlbum>,
        val members: Map<Uuid, List<Uuid>>,
        val pageSize: Int = 2,
    ) : EnteLibrary {
        val exported = mutableListOf<EntePhoto>()
        override suspend fun isEnteInstalled() = true
        override suspend fun openDriveKey() = true
        override suspend fun photos(cursor: String?): EntePhotosPage {
            val from = cursor?.toInt() ?: 0
            val to = minOf(from + pageSize, library.size)
            return EntePhotosPage(library.subList(from, to), to.takeIf { it < library.size }?.toString())
        }
        override suspend fun albums() = albums
        override suspend fun albumMembers(album: Uuid) = members[album].orEmpty()
        override suspend fun photosByUniqueId(ids: List<Uuid>) = ids.mapNotNull { id -> library.firstOrNull { it.uniqueId == id } }
        override suspend fun exportOriginal(photo: EntePhoto, onProgress: (Float) -> Unit): String {
            onProgress(1f)
            exported += photo
            return "/tmp/${photo.title}"
        }
    }

    // The Ente drive granted, so the picker goes straight to the library.
    private suspend fun permission(): ExtendPermissionViewModel {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(
            ApiCredentials.create(domain = OdinId("me.example.com"), clientAccessToken = "t", sharedSecret = SecureByteArray(ByteArray(16) { 1 })),
        )
        val context = """
            {"caller":{"odinId":"me.example.com","securityLevel":"owner"},
             "permissionContext":{"permissionGroups":[{"driveGrants":[
               {"permissionedDrive":{"drive":{"alias":"${EntePhotosDrive.ALIAS}","type":"${EntePhotosDrive.TYPE}"},"permission":"read"}}]}]}}
        """.trimIndent()
        val engine = MockEngine { respond(context, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        return ExtendPermissionViewModel(
            SecurityContextProvider(HttpClient(engine), credentials),
            credentials,
            EventBus(),
            // The storage key's own check is ExtendPermissionViewModelTest's; here, the read grant is enough.
            getEntePhotosPermissionExtensionConfig().let { c -> c.copy(drives = c.drives.map { it.copy(requireStorageKey = false) }) },
            autoCheck = false,
        )
    }

    private suspend fun EntePhotosViewModel.settled(check: (EntePhotosUiState) -> Boolean) =
        withTimeout(10.seconds) { uiState.first { !it.loading && check(it) } }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val beach = item("beach.jpg")
    private val clip = item("clip.mp4", video = true)
    private val secret = item("secret.jpg")
    private val huge = item("huge.mp4", video = true, size = MAX_ENTE_VIDEO_BYTES + 1)
    private val holiday = EnteAlbum(Uuid.random(), "Holiday", "album", hidden = false)
    private val hidden = EnteAlbum(Uuid.random(), ".hidden", "album", hidden = true)
    private val favorites = EnteAlbum(Uuid.random(), "Favorites", "favorites", hidden = false)

    private fun library() = FakeLibrary(
        library = listOf(secret, beach, clip, huge),
        albums = listOf(holiday, hidden, favorites),
        members = mapOf(
            holiday.id to listOf(clip.uniqueId!!, beach.uniqueId!!),
            hidden.id to listOf(secret.uniqueId!!),
            favorites.id to emptyList(),
        ),
    )

    @Test
    fun theLibraryLeavesOutPhotosInHiddenAlbumsAndOffersOnlyVisibleAlbums() = runBlocking<Unit> {
        val vm = EntePhotosViewModel(library(), permission())
        vm.open()
        val first = vm.settled { it.access == EntePhotosAccess.Granted && it.photos.isNotEmpty() }
        assertEquals(listOf("Favorites", "Holiday"), first.albums.map { it.name })
        // The first page was the hidden photo and beach: only beach shows.
        assertEquals(listOf(beach), first.photos)
        vm.loadMore()
        val all = vm.settled { !it.canLoadMore }
        assertEquals(listOf(beach, clip, huge), all.photos)
    }

    @Test
    fun anAlbumShowsItsMembersAndAnEmptyAlbumIsEmpty() = runBlocking<Unit> {
        val vm = EntePhotosViewModel(library(), permission())
        vm.open()
        vm.settled { it.photos.isNotEmpty() }
        vm.showAlbum(holiday.id)
        val album = vm.settled { it.album == holiday.id && it.photos.isNotEmpty() }
        assertEquals(listOf(clip, beach), album.photos)
        assertFalse(album.canLoadMore)
        vm.showAlbum(favorites.id)
        val empty = vm.settled { it.album == favorites.id }
        assertTrue(empty.photos.isEmpty())
        assertFalse(empty.loadFailed)
    }

    @Test
    fun picksSurviveSwitchingAlbumsAndOversizedVideosCantBePicked() = runBlocking<Unit> {
        val fake = library()
        val vm = EntePhotosViewModel(fake, permission())
        vm.open()
        vm.settled { it.photos.isNotEmpty() }
        vm.toggle(beach)
        vm.toggle(huge)
        vm.showAlbum(holiday.id)
        vm.settled { it.album == holiday.id && it.photos.isNotEmpty() }
        vm.toggle(clip)
        assertEquals(listOf(beach.fileId, clip.fileId), vm.uiState.value.selected)

        val picked = withTimeout(10.seconds) {
            vm.send()
            vm.events.first { it is EntePhotosEvent.Picked } as EntePhotosEvent.Picked
        }
        assertEquals(listOf("/tmp/beach.jpg", "/tmp/clip.mp4"), picked.paths)
        assertEquals(listOf(beach, clip), fake.exported)
        val after = vm.settled { !it.preparing }
        assertTrue(after.selected.isEmpty())
    }
}
