package id.homebase.core.widget

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Shared input for a Homebase id (domain). Stores the text with spaces internally — the visual
 * transformation renders them as dots — which lets mobile IMEs behave predictably (no auto-period
 * after double-space, etc.). Callers should feed [value] with the space-encoded form and normalize
 * via `cleanDomain()` on submit.
 */
@Composable
fun HomebaseIdField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
) {
    val baseModifier = modifier.fillMaxWidth()
    val fieldModifier = if (focusRequester != null) {
        baseModifier.focusRequester(focusRequester)
    } else {
        baseModifier
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = fieldModifier,
        label = label,
        placeholder = placeholder,
        singleLine = true,
        isError = isError,
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        visualTransformation = remember {
            VisualTransformation { text ->
                TransformedText(
                    text = AnnotatedString(text.text.replace(' ', '.')),
                    offsetMapping = OffsetMapping.Identity,
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction?.invoke() },
            onNext = { onImeAction?.invoke() },
            onGo = { onImeAction?.invoke() },
            onSend = { onImeAction?.invoke() },
            onSearch = { onImeAction?.invoke() },
        ),
    )
}
