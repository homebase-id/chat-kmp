package id.homebase.core.ui.screens.webdrop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.ui.screens.webdrop.components.WebDropComposeSheet
import id.homebase.core.ui.screens.webdrop.components.WebDropRowCard
import id.homebase.core.ui.screens.webdrop.model.DropRow
import id.homebase.core.ui.screens.webdrop.model.DropStatus
import id.homebase.core.ui.screens.webdrop.model.PickedDropFile
import id.homebase.core.ui.screens.webdrop.model.WebDropTtlChoice
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.webdrop.WebDropManifestEntry
import id.homebase.core.webdrop.WebDropReceiptContent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Renders the view-only compose sheet and drop rows, light and dark, to PNGs for design review.
 * Set WEBDROP_SHOTS_DIR to write the images; without it the states are only composed.
 */
@OptIn(ExperimentalTestApi::class)
class WebDropViewOnlyShotsTest {

    private val outDir: File? = System.getenv("WEBDROP_SHOTS_DIR")?.let(::File)?.also { it.mkdirs() }

    private val only: String? = System.getenv("WEBDROP_SHOTS_ONLY")

    private val koin = koinApplication {
        modules(module { single<FileOperationsProvider> { StageFakeFileOps(mutableMapOf()) } })
    }

    private class Shot(
        val name: String,
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
        val widthDp: Int = PHONE_W,
        val heightDp: Int = PHONE_H,
        val content: @Composable () -> Unit,
    )

    private fun picked(name: String) = PickedDropFile(path = "/tmp/$name", name = name, contentType = "image/jpeg", size = 0)

    private val files = listOf(picked("passport-scan.jpg"), picked("lease-agreement-signed.pdf"))
    private val longFiles = listOf(
        picked("Quarterly board pack - confidential - final final v7 (Henrietta's edits).pdf"),
        picked("IMG_20260912_181155_HDR.jpg"),
    )
    private val compose = WebDropUiState(composeOpen = true, pickedFiles = files)

    private fun receipt(name: String, viewOnly: Boolean?, recipient: String? = null, ttl: Long = -1) = WebDropReceiptContent(
        name = name,
        files = listOf(WebDropManifestEntry("wdr_dat1", "a.jpg", "image/jpeg", 1), WebDropManifestEntry("wdr_dat2", "b.pdf", "application/pdf", 1)),
        url = "https://frodo.dotyou.cloud/apps/web-drop#k",
        ttl = ttl,
        createdAt = UnixTimeUtc.now().milliseconds - 3_600_000,
        recipientName = recipient,
        viewOnly = viewOnly,
    )

    private fun row(receipt: WebDropReceiptContent, status: DropStatus) = DropRow(Uuid.random(), Uuid.random(), Uuid.random(), receipt, status)

    private val soon get() = UnixTimeUtc.now().milliseconds + 2 * 3_600_000

    @Composable
    private fun Rows(rows: List<DropRow>) {
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rows.forEach { WebDropRowCard(row = it, onCopyLink = {}, onRevoke = {}, onClear = {}) }
        }
    }

    @Composable
    private fun Sheet(state: WebDropUiState) {
        var s by remember { mutableStateOf(state) }
        WebDropComposeSheet(uiState = s, onAction = { if (it is WebDropUiAction.ViewOnlyToggled) s = s.copy(viewOnly = it.enabled) })
    }

    private val rowSet
        get() = listOf(
            row(receipt("Passport for the landlord", viewOnly = true, recipient = "Rosie Cotton"), DropStatus.Waiting),
            row(receipt("Tax return 2025", viewOnly = true), DropStatus.Opened(soon)),
            row(receipt("Holiday photos", viewOnly = null, ttl = 1), DropStatus.Expiring(soon)),
            row(receipt("Lease scan", viewOnly = true), DropStatus.Removed),
        )

    private val longRows
        get() = listOf(
            row(
                receipt(
                    "Quarterly board pack - confidential - final final v7 with Henrietta's edits",
                    viewOnly = true,
                    recipient = "Peregrin Took of the Great Smials, Tuckborough",
                ),
                DropStatus.Waiting,
            ),
            row(receipt("Tax return 2025", viewOnly = true), DropStatus.Opened(soon)),
        )

    private val shots = listOf(
        Shot("01-sheet-empty") { Sheet(WebDropUiState(composeOpen = true)) },
        Shot("02-sheet-view-only-off") { Sheet(compose) },
        Shot("03-sheet-view-only-on") { Sheet(compose.copy(viewOnly = true)) },
        Shot("04-sheet-view-only-ttl") { Sheet(compose.copy(viewOnly = true, ttlChoice = WebDropTtlChoice.SevenDays)) },
        Shot("05-sheet-for-someone") {
            Sheet(compose.copy(viewOnly = true, introExpanded = true, recipientName = "Rosie Cotton"))
        },
        Shot("06-sheet-creating") { Sheet(compose.copy(viewOnly = true, isCreating = true)) },
        Shot("07-sheet-error") { Sheet(compose.copy(viewOnly = true, error = WebDropError.CreateFailed)) },
        Shot("08-sheet-link-ready") { Sheet(compose.copy(viewOnly = true, createdUrl = "https://frodo.dotyou.cloud/apps/web-drop/d/3f2a#k=abc")) },
        Shot("09-sheet-long-names") { Sheet(compose.copy(pickedFiles = longFiles, viewOnly = true)) },
        Shot("10-sheet-font-scale", fontScale = 1.6f) { Sheet(compose.copy(viewOnly = true)) },
        Shot("11-sheet-rtl", rtl = true) { Sheet(compose.copy(viewOnly = true)) },
        Shot("12-sheet-small", widthDp = 360, heightDp = 640) { Sheet(compose.copy(viewOnly = true)) },
        Shot("20-rows") { Rows(rowSet) },
        Shot("21-rows-long-names") { Rows(longRows) },
        Shot("22-rows-font-scale", fontScale = 1.6f) { Rows(rowSet) },
        Shot("23-rows-rtl", rtl = true) { Rows(rowSet) },
        Shot("24-rows-small", widthDp = 360, heightDp = 640, fontScale = 1.3f) { Rows(longRows) },
    )

    @Test
    fun viewOnlyRendersEveryState() {
        for (dark in listOf(false, true)) {
            for (shot in shots.filter { only == null || it.name.startsWith(only) }) render(shot, dark)
        }
    }

    private fun render(shot: Shot, dark: Boolean) = runDesktopComposeUiTest(
        width = (shot.widthDp * SCALE).toInt(),
        height = (shot.heightDp * SCALE).toInt(),
    ) {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(SCALE, shot.fontScale),
                LocalLayoutDirection provides if (shot.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                KoinIsolatedContext(koin) {
                    HomebaseTheme(darkTheme = dark, updatesSystemChrome = false) {
                        shot.content()
                    }
                }
            }
        }
        mainClock.advanceTimeBy(SETTLE_MS)
        save(shot.name, dark)
    }

    private fun ComposeUiTest.save(name: String, dark: Boolean) {
        // A bottom sheet adds its own root; the last one holds the whole scene.
        val roots = onAllNodes(isRoot())
        val image = roots[roots.fetchSemanticsNodes().size - 1].captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
        outDir?.let { dir ->
            ImageIO.write(image.toAwtImage(), "png", File(dir, "$name-${if (dark) "dark" else "light"}.png"))
        }
    }

    private companion object {
        const val SCALE = 2f
        const val PHONE_W = 412
        const val PHONE_H = 892
        const val SETTLE_MS = 1_500L
    }
}
