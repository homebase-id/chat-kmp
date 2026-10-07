package id.homebase.chat.viewonce

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ViewOnceBubbleTest {
    private fun render(opensOnPhoneOnly: Boolean, isOutgoing: Boolean = false) = runComposeUiTest {
        setContent {
            MaterialTheme {
                ViewOnceBubble(
                    descriptor = ViewOnceDescriptor(ViewOnceDescriptor.KIND_IMAGE),
                    isOutgoing = isOutgoing,
                    opensOnPhoneOnly = opensOnPhoneOnly,
                )
            }
        }
        val node = onNodeWithText("Open on your phone")
        if (opensOnPhoneOnly && !isOutgoing) node.assertExists() else node.assertDoesNotExist()
    }

    @Test
    fun desktopAndWebShowOpenOnYourPhone() = render(opensOnPhoneOnly = true)

    @Test
    fun mobileDoesNotShowIt() = render(opensOnPhoneOnly = false)
}
