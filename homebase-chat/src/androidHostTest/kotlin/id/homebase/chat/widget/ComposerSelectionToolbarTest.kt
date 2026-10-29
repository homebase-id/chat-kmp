package id.homebase.chat.widget

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.SideEffect
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import android.widget.Magnifier
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [NoOpMagnifier::class])
class ComposerSelectionToolbarTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var state: RichTextState

    private fun setComposer() {
        rule.setContent {
            state = rememberRichTextState()
            SideEffect { if (state.annotatedString.text.isEmpty()) state.setText("hello world") }
            RichTextEditor(
                state = state,
                modifier = Modifier.fillMaxWidth().testTag(TAG),
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun longPressShowsSelectionToolbarWithoutCrashing() {
        setComposer()
        rule.onNodeWithTag(TAG).performTouchInput { longClick(Offset(20f, centerY)) }
        rule.waitForIdle()
        assertEquals("hello world", state.annotatedString.text)
    }

    @Test
    fun tappingTheCursorOfAFocusedFieldShowsToolbarWithoutCrashing() {
        setComposer()
        rule.onNodeWithTag(TAG).performTouchInput { click(Offset(20f, centerY)) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG).performTouchInput { click(Offset(20f, centerY)) }
        rule.waitForIdle()
        assertEquals("hello world", state.annotatedString.text)
    }

    private companion object {
        const val TAG = "composer"
    }
}

@Implements(Magnifier::class)
class NoOpMagnifier {
    @Implementation
    fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit

    @Implementation
    fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit

    @Implementation
    fun update() = Unit

    @Implementation
    fun dismiss() = Unit
}
