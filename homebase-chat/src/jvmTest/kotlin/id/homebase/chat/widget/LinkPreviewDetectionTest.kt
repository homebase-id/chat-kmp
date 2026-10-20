package id.homebase.chat.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.mohamedrejeb.richeditor.model.RichTextState
import com.russhwolf.settings.PreferencesSettings
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.link.LinkPreview
import id.homebase.api.client.link.LinkPreviewProvider
import id.homebase.chat.services.renderer.LinkPreviewRenderer
import id.homebase.chat.services.renderer.PayloadRenderer
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.theme.HomebaseTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LinkPreviewDetectionTest {

    private val url = "https://example.com/article"
    private val loadingLabel = "Loading link preview"
    private val cancelLabel = "Cancel"

    private class GatedLinkPreviewProvider : LinkPreviewProvider(
        HttpClient(MockEngine { respond("") }), CredentialsManager()
    ) {
        val gate = CompletableDeferred<Result<LinkPreview?>>()
        val requested = mutableListOf<String>()

        override suspend fun getLinkPreview(url: String): LinkPreview? {
            requested += url
            return gate.await().getOrThrow()
        }
    }

    private fun preview(url: String) = LinkPreview(
        title = "Article", url = url, description = "", imageUrl = null, imageHeight = null, imageWidth = null,
    )

    private class Harness(val provider: GatedLinkPreviewProvider) {
        val text = RichTextState()
        var renderers by mutableStateOf<List<PayloadRenderer>>(emptyList())
        val koin = koinApplication {
            modules(
                module {
                    single {
                        UserPreferences(PreferencesSettings(Preferences.userRoot().node("/id/homebase/test")))
                    }
                    single<LinkPreviewProvider> { provider }
                }
            )
        }
    }

    @Composable
    private fun Composer(harness: Harness) {
        KoinIsolatedContext(harness.koin) {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                MessageInputBar(
                    textFieldState = harness.text,
                    recordingData = null,
                    focusRequester = FocusRequester(),
                    editExistingMode = false,
                    showingEmojiSheet = false,
                    onEmojiClick = {},
                    onKeyboardClick = {},
                    onFocused = {},
                    onAddAttachmentClick = {},
                    onCameraClick = {},
                    onRecordingStarted = {},
                    onRecordingStopped = {},
                    onRecordingCancelled = {},
                    onRecordingHelp = {},
                    payloadRenderers = harness.renderers,
                    onPayloadRenderersChange = { harness.renderers = it },
                    onSendMessage = { _, _ -> },
                    onCancelEdit = {},
                )
            }
        }
    }

    @Test
    fun `editing text around the url while the fetch is in flight still stages the preview`() =
        runDesktopComposeUiTest(width = 600, height = 800) {
            val harness = Harness(GatedLinkPreviewProvider())
            setContent { Composer(harness) }

            runOnIdle { harness.text.setText(url) }
            waitUntil(timeoutMillis = 5_000) { harness.provider.requested.isNotEmpty() }

            runOnIdle { harness.text.setText("$url nice") }
            waitForIdle()
            harness.provider.gate.complete(Result.success(preview(url)))

            waitUntil(timeoutMillis = 5_000) { harness.renderers.any { it is LinkPreviewRenderer } }
            assertEquals(
                listOf<PayloadRenderer>(LinkPreviewRenderer(preview(url))),
                harness.renderers,
            )
        }

    @Test
    fun `a loading card shows while fetching and is replaced by the preview`() =
        runDesktopComposeUiTest(width = 600, height = 800) {
            val harness = Harness(GatedLinkPreviewProvider())
            setContent { Composer(harness) }

            runOnIdle { harness.text.setText(url) }
            waitUntil(timeoutMillis = 5_000) { harness.provider.requested.isNotEmpty() }
            onNodeWithContentDescription(loadingLabel).assertExists()

            harness.provider.gate.complete(Result.success(preview(url)))
            waitUntil(timeoutMillis = 5_000) { harness.renderers.any { it is LinkPreviewRenderer } }
            onNodeWithContentDescription(loadingLabel).assertDoesNotExist()
        }

    @Test
    fun `a failed fetch removes the loading card`() =
        runDesktopComposeUiTest(width = 600, height = 800) {
            val harness = Harness(GatedLinkPreviewProvider())
            setContent { Composer(harness) }

            runOnIdle { harness.text.setText(url) }
            waitUntil(timeoutMillis = 5_000) { harness.provider.requested.isNotEmpty() }
            onNodeWithContentDescription(loadingLabel).assertExists()

            harness.provider.gate.complete(Result.failure(IllegalStateException("boom")))
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithContentDescription(loadingLabel).fetchSemanticsNodes().isEmpty()
            }
            assertTrue(harness.renderers.isEmpty())
        }

    @Test
    fun `dismissing the loading card keeps the url dismissed`() =
        runDesktopComposeUiTest(width = 600, height = 800) {
            val harness = Harness(GatedLinkPreviewProvider())
            setContent { Composer(harness) }

            runOnIdle { harness.text.setText(url) }
            waitUntil(timeoutMillis = 5_000) { harness.provider.requested.isNotEmpty() }
            onNodeWithContentDescription(cancelLabel).performClick()
            waitForIdle()
            onNodeWithContentDescription(loadingLabel).assertDoesNotExist()

            harness.provider.gate.complete(Result.success(preview(url)))
            waitForIdle()
            runOnIdle { harness.text.setText("$url again") }
            waitForIdle()
            assertTrue(harness.renderers.isEmpty())
            assertEquals(listOf(url), harness.provider.requested)
        }
}
