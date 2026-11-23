package id.homebase.core.ui.screens.feed

import com.russhwolf.settings.PreferencesSettings
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.settings.UserPreferences
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleWebRetryTest {
    private val dispatcher = StandardTestDispatcher()
    private val node = Preferences.userRoot().node("articleWebRetryTest")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        node.removeNode()
    }

    @Test
    fun retryAfterAPageErrorClearsTheError() = runTest(dispatcher) {
        val credentialsManager = CredentialsManager()
        credentialsManager.setActiveCredentials(
            ApiCredentials.create(OdinId("frodo.baggins.demo.rocks"), "token", SecureByteArray(byteArrayOf(1, 2, 3))),
        )
        val viewModel = ArticleWebViewModel(credentialsManager, UserPreferences(PreferencesSettings(node)))
        val post = article()
        viewModel.load(post, isSystemDark = false)
        advanceUntilIdle()
        val url = assertNotNull(viewModel.uiState.value.url)

        viewModel.onPageStarted()
        viewModel.onPageError("net::ERR_INTERNET_DISCONNECTED")
        assertEquals("net::ERR_INTERNET_DISCONNECTED", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)

        viewModel.load(post, isSystemDark = false)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertEquals(url, viewModel.uiState.value.url)
    }

    private fun article() = FeedPostItem(
        id = Uuid.random(),
        fileId = Uuid.random(),
        globalTransitId = null,
        driveId = Uuid.random(),
        keyHeader = KeyHeader.empty(),
        payloads = emptyList(),
        caption = "",
        type = PostType.Article,
        channelId = Uuid.random().toString(),
        slug = "post",
        reactAccess = ReactAccess.All,
        embeddedPost = null,
        userDateMs = 0,
        createdMs = 0,
        previewThumbnail = null,
        reactionPreview = null,
        senderOdinId = null,
        originalAuthor = null,
        versionTag = null,
        ownReactions = emptyList(),
        commentCount = 0,
        isEncrypted = false,
        acl = null,
    )
}
