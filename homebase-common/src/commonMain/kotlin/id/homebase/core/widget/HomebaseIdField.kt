package id.homebase.core.widget

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

// No theme token lands on 6dp (small is 8, extraSmall is 4) and the radius was picked by hand.
val HomebaseFieldShape = RoundedCornerShape(6.dp)

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
    supportingText: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    textStyle: TextStyle = LocalTextStyle.current,
    isError: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    focusRequester: FocusRequester? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
) {
    val baseModifier = modifier
        .fillMaxWidth()
        // Declare this as a username field so password managers (Bitwarden, etc.) recognise
        // it and offer the inline keyboard suggestion. Compose Multiplatform 1.8 reworked
        // autofill to require an explicit ContentType — before that the field was matched by
        // the framework's heuristics, which is why the suggestion silently stopped appearing
        // after a Compose bump.
        .semantics { contentType = ContentType.Username }
    val fieldModifier = if (focusRequester != null) {
        baseModifier.focusRequester(focusRequester)
    } else {
        baseModifier
    }

    // Owned here, not a parameter, so every call site gets the focus treatment unchanged.
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val containerTarget = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
        // Not solid errorContainer: it is (147,0,6) in the dark scheme, and a 400x56 block of it
        // makes a recoverable typo read as a system failure.
        isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        focused -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    // The tonal fill carries the state change; a 1dp-to-2dp outline on its own is near invisible
    // against a pale surface. Default spring spec — MaterialTheme.motionScheme is internal in
    // Compose Multiplatform's material3 1.9.0, so no theme-tracking spec is reachable here.
    val containerColor by animateColorAsState(targetValue = containerTarget)

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = fieldModifier,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        supportingText = supportingText,
        trailingIcon = trailingIcon,
        singleLine = true,
        isError = isError,
        enabled = enabled,
        readOnly = readOnly,
        interactionSource = interactionSource,
        shape = HomebaseFieldShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = containerColor,
            unfocusedContainerColor = containerColor,
            disabledContainerColor = containerColor,
            errorContainerColor = containerColor,
        ),
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
