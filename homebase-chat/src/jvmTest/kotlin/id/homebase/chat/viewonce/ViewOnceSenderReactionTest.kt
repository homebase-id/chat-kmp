package id.homebase.chat.viewonce

import kotlinx.coroutines.flow.MutableStateFlow
import id.homebase.api.client.contacts.AvatarResolver
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import com.russhwolf.settings.PreferencesSettings
import id.homebase.chat.services.decodeReactionCode
import id.homebase.chat.services.mapToMessageData
import id.homebase.chat.widget.MessageItem
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** The emoji a recipient picks in the viewer lands on the sender's copy and stays after `_vo` and their delete. */
@OptIn(ExperimentalTestApi::class, ExperimentalSharedTransitionApi::class)
class ViewOnceSenderReactionTest {

    private val now = Clock.System.now().toEpochMilliseconds()
    private val thumbsUp = "👍"

    // The sender's own copy after the recipient's viewer emoji and `_vo` both drained: the server folds
    // group reactions into this summary, and the recipient's local delete never touches the sender's file.
    private val sendersCopy = viewOnceHeader(
        author = VO_OWNER,
        createdMs = now - DAY_MS,
        payloadsJson = "null",
        reactionPreviewJson = """{"reactions":{""" +
            """"a":{"key":"a","count":1,"reactionContent":"{\"emoji\":\"$thumbsUp\"}"},""" +
            """"b":{"key":"b","count":1,"reactionContent":"{\"emoji\":\"${ViewOnceSignal.OPENED_CODE}\"}"}""" +
            """}}""",
    )

    private val koin = koinApplication {
        modules(
            module {
                single { AvatarResolver(MutableStateFlow(emptyList())) }
                single { UserPreferences(PreferencesSettings(Preferences.userRoot().node("/id/homebase/test/viewonce-sender"))) }
                single { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
            },
        )
    }

    @Test
    fun theSendersCopyKeepsTheViewerEmojiAlongsideTheOpenedSignal() = runTest {
        val credentials = ownerCredentials()
        val model = assertNotNull(mapToMessageData(sendersCopy, credentials))

        val codes = model.reactionPreview?.reactions?.values.orEmpty().map { decodeReactionCode(it.reactionContent) }
        assertTrue(thumbsUp in codes, "the viewer emoji survives mapping: $codes")
        assertEquals(ViewOnceState.Opened, ViewOnceRules.stateOf(model, now, credentials.requireActiveDomain()))
    }

    @Test
    fun theSendersBubbleShowsTheEmojiAndHidesTheSignal() = runComposeUiTest {
        val model = assertNotNull(runBlocking { mapToMessageData(sendersCopy, ownerCredentials()) })

        setContent {
            KoinIsolatedContext(koin) {
                HomebaseTheme(darkTheme = false) {
                    SharedTransitionLayout {
                        AnimatedVisibility(visible = true) {
                            MessageItem(
                                message = model,
                                userDefaultReactions = persistentListOf(thumbsUp),
                                decryptedFiles = persistentMapOf(),
                                currentOdinId = VO_OWNER,
                                animatedVisibilityScope = this@AnimatedVisibility,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                onUiAction = {},
                                downloadingFiles = emptySet(),
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()

        onNodeWithText("Opened").assertExists()
        onNodeWithText(thumbsUp).assertExists()
        onNodeWithText(ViewOnceSignal.OPENED_CODE, substring = true).assertDoesNotExist()
    }
}
