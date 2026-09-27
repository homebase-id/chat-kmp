package id.homebase.chat.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runDesktopComposeUiTest
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DeliveryStatusSizeTest {

    @Test
    fun deliveredTickKeepsItsOwnHeightInsideThePendingSizedSlot() = runDesktopComposeUiTest {
        setContent {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                DeliveryStatus(
                    isPendingSend = false,
                    deliveryStatus = ChatDeliveryStatus.Delivered.value,
                    contentColor = Color.Black,
                )
            }
        }
        onNodeWithContentDescription("Delivered").assertHeightIsEqualTo(DELIVERY_ICON_SIZE)
    }
}
