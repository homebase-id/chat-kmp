package id.homebase.core.ui.screens.moments

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.russhwolf.settings.PreferencesSettings
import id.homebase.api.client.KeyHeader
import id.homebase.core.moments.MomentsViewMode
import id.homebase.core.moments.services.MomentFeedItem
import id.homebase.core.moments.services.MomentsVideoSession
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

@OptIn(ExperimentalTestApi::class, ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
class MomentsWideScreenshotTest {
    private val outDir = File("../build/polish-shots").apply { mkdirs() }

    private fun moment(index: Int) = MomentFeedItem(
        id = Uuid.random(),
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        keyHeader = KeyHeader.empty(),
        payloads = emptyList(),
        description = "Moment $index on the ridge at first light",
        userDateMs = 1_790_000_000_000L - index * 3_600_000L,
        createdMs = 1_790_000_000_000L - index * 3_600_000L,
        previewThumbnail = null,
        reactionPreview = null,
        senderOdinId = null,
        originalAuthor = null,
        source = null,
        recipients = emptyList(),
        commentsEnabled = true,
        versionTag = null,
        ownReactions = emptyList(),
    )

    private val moments = (1..14).map(::moment)

    @BeforeTest
    fun startDi() {
        startKoin { 
            modules(
                module {
                    single { MomentsVideoSession() }
                    single { UserPreferences(PreferencesSettings(Preferences.userRoot().node("momentsWideScreenshotTest"))) }
                },
            )
         }
    }

    @AfterTest
    fun stopDi() = stopKoin()

    private fun ComposeUiTest.show(
        items: List<MomentFeedItem>,
        viewMode: MomentsViewMode,
        dark: Boolean,
        onDetail: (Uuid) -> Unit = {},
    ) = setContent {
        HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
            Surface {
                val mode = remember { mutableStateOf(viewMode) }
                MomentsAdaptiveContent(
                    uiState = MomentsFeedUiState(moments = items),
                    viewMode = mode.value,
                    onViewModeChange = { mode.value = it },
                    onAlbumZoomChange = {},
                    onCreateMoment = {},
                    onProfileClick = {},
                    onOpenMoment = { _, _ -> },
                    onAddReaction = { _, _ -> },
                    onDeleteFailedMoment = {},
                    onDismissUpload = {},
                    detailPane = { id ->
                        onDetail(id)
                        val moment = items.first { it.id == id }
                        SharedTransitionLayout {
                            AnimatedContent(targetState = Unit) { _ ->
                                DetailContent(
                                    uiState = MomentDetailUiState(moment = moment, isLoading = false),
                                    onAction = {},
                                    onNavigateBack = null,
                                    snackbarHostState = remember { SnackbarHostState() },
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this@AnimatedContent,
                                    isActivePage = true,
                                    commentsDocked = true,
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        waitForIdle()
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        val colors = HashSet<Int>()
        for (x in 0 until image.width step 5) for (y in 0 until image.height step 5) colors += image.getRGB(x, y)
        assertTrue(colors.size > 8, "$name rendered blank")
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }

    private fun shot(name: String, width: Int, height: Int, mode: MomentsViewMode) {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(width, height) {
                show(moments, mode, dark)
                save("$name-${if (dark) "dark" else "light"}")
            }
        }
    }

    @Test
    fun rendersEveryWidthInBothThemes() {
        shot("phone-timeline", 411, 800, MomentsViewMode.Timeline)
        shot("phone-album", 411, 800, MomentsViewMode.Album)
        shot("capped-timeline", 900, 800, MomentsViewMode.Timeline)
        shot("capped-album", 900, 800, MomentsViewMode.Album)
        shot("pane-timeline-1135", 1135, 800, MomentsViewMode.Timeline)
        shot("pane-album-1135", 1135, 800, MomentsViewMode.Album)
        shot("pane-timeline-1855", 1855, 1000, MomentsViewMode.Timeline)
    }

    @Test
    fun anEmptyFeedKeepsItsEmptyStateFullWidth() = runDesktopComposeUiTest(1135, 800) {
        var detailShown = false
        show(emptyList(), MomentsViewMode.Timeline, dark = false, onDetail = { detailShown = true })
        save("pane-empty-light")
        assertTrue(!detailShown)
    }

    @Test
    fun clickingACardMovesTheSelectionIntoThePane() = runDesktopComposeUiTest(1135, 800) {
        val shown = mutableListOf<Uuid>()
        show(moments, MomentsViewMode.Timeline, dark = false, onDetail = { if (shown.lastOrNull() != it) shown += it })
        waitForIdle()
        assertEquals(moments.first().id, shown.last())
        onAllNodes(hasClickAction() and hasAnyDescendant(hasText(moments[1].description)))
            .onFirst()
            .performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(moments[1].id, shown.last())
        save("pane-selected-second-light")
    }
}
