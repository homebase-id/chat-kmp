package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.chat.services.ChatDeliveryStatus
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTestApi::class)
class DeliveryStatusTest {

    @Test
    fun rendersForPendingSend() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DeliveryStatus(
                    isPendingSend = true,
                    deliveryStatus = 0,
                    contentColor = Color.White,
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun rendersForSentStatus() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DeliveryStatus(
                    isPendingSend = false,
                    deliveryStatus = ChatDeliveryStatus.Sent.value,
                    contentColor = Color.White,
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun rendersForDeliveredStatus() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DeliveryStatus(
                    isPendingSend = false,
                    deliveryStatus = ChatDeliveryStatus.Delivered.value,
                    contentColor = Color.White,
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun rendersForReadStatus() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DeliveryStatus(
                    isPendingSend = false,
                    deliveryStatus = ChatDeliveryStatus.Read.value,
                    contentColor = Color.White,
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun rendersForFailedStatus() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DeliveryStatus(
                    isPendingSend = false,
                    deliveryStatus = ChatDeliveryStatus.Failed.value,
                    contentColor = Color.White,
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun rendersForStalePendingSend() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DeliveryStatus(
                    isPendingSend = true,
                    deliveryStatus = 0,
                    contentColor = Color.White,
                    pendingSince = Clock.System.now() - 2.minutes,
                )
            }
        }
        waitForIdle()
    }
}
