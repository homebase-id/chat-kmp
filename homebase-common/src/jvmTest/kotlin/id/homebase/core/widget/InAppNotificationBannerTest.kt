package id.homebase.core.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runSkikoComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import id.homebase.core.notifications.RichNotificationData
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class InAppNotificationBannerTest {

    private val koin = koinApplication {
        modules(
            module {
                single<ImageLoader> { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
            },
        )
    }

    private val notification = RichNotificationData(
        notificationId = 1,
        channelId = "messages",
        conversationId = null,
        title = "Frodo",
        body = "Second breakfast?",
        senderName = "Frodo",
        senderId = "frodo.invalid",
        senderImageBytes = null,
        timestamp = 0,
        payloadData = emptyMap(),
    )

    @Test
    fun `dismissing slides the banner out with its content`() = runSkikoComposeUiTest {
        var event by mutableStateOf<RichNotificationData?>(notification)
        setContent {
            KoinIsolatedContext(koin) {
                MaterialTheme {
                    InAppNotificationBanner(event = event, visible = event != null, onTap = {})
                }
            }
        }
        waitForIdle()
        onNodeWithText(notification.body).assertExists()

        mainClock.autoAdvance = false
        event = null
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        onNodeWithText(notification.body).assertExists()

        mainClock.autoAdvance = true
        waitForIdle()
        onNodeWithText(notification.body).assertDoesNotExist()
    }
}
