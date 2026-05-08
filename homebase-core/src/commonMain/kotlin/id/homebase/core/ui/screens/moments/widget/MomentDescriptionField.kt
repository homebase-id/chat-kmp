package id.homebase.core.ui.screens.moments.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import id.homebase.chat.widget.RichTextEditorButtons
import id.homebase.resources.MR
import id.homebase.resources.chat_message_emoji_options
import id.homebase.resources.moments_compose_description_hint
import org.jetbrains.compose.resources.stringResource

/**
 * Moments-only fork of `MessageTextFieldForAttachment` without the trailing
 * send IconButton. The moments compose flow advances via the screen's
 * bottomBar Continue button, not from inside the composer — see
 * `MomentComposeScreen`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentDescriptionField(
    modifier: Modifier = Modifier,
    state: RichTextState,
    onSmileyClick: () -> Unit = {},
) {
    Column(modifier = modifier) {
        RichTextEditorButtons(
            modifier = Modifier.fillMaxWidth(),
            state = state,
            enabled = true,
        )
        RichTextEditor(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(stringResource(MR.string.moments_compose_description_hint))
            },
            leadingIcon = {
                IconButton(onClick = onSmileyClick) {
                    Icon(
                        imageVector = Icons.Default.EmojiEmotions,
                        contentDescription = stringResource(
                            MR.string.chat_message_emoji_options
                        )
                    )
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = RichTextEditorDefaults.richTextEditorColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            minLines = 1,
            maxLines = 3,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default,
            )
        )
    }
}
