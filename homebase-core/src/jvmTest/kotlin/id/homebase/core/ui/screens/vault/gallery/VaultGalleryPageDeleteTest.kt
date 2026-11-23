@file:OptIn(ExperimentalTestApi::class)

package id.homebase.core.ui.screens.vault.gallery

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.core.media.MediaPager
import kotlin.test.Test
import kotlin.test.assertEquals

class VaultGalleryPageDeleteTest {

    @Test
    fun deletingTheLastPageLandsOnTheNewLastPage() = assertDeleteLandsOn(initialPage = 2, deleted = "Page C", expected = "Page B")

    @Test
    fun deletingAMiddlePageShowsThePageAfterIt() = assertDeleteLandsOn(initialPage = 1, deleted = "Page B", expected = "Page C")

    private fun assertDeleteLandsOn(initialPage: Int, deleted: String, expected: String) = runComposeUiTest {
        var pages by mutableStateOf(listOf("Page A", "Page B", "Page C"))
        lateinit var pagerState: PagerState
        setContent {
            pagerState = rememberPagerState(initialPage = initialPage) { pages.size }
            MediaPager(pageCount = pages.size, state = pagerState) { page -> Text(pages[page]) }
        }
        onNodeWithText(deleted).assertIsDisplayed()

        pages = pages - deleted
        waitForIdle()

        assertEquals(1, pagerState.currentPage)
        onNodeWithText(expected).assertIsDisplayed()
    }
}
