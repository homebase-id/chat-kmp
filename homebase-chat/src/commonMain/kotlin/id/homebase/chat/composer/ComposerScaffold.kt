package id.homebase.chat.composer

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Shared building blocks for the in-chat typed-message composers (Event,
 * Groodle, …), modeled on the Google Calendar quick-create screen: a borderless
 * title with a focus-aware underline, then flat icon-led rows with inline
 * borderless fields. Kept in a neutral package so every composer uses ONE copy
 * rather than redefining these privately.
 */

/** Vertical gap reserved for the icon column so unlabeled sub-rows align under the row text. */
internal val ComposerRowIconGap = 20.dp

/**
 * A borderless headline title field with a focus-aware underline (brand-tinted
 * and thicker while focused). [placeholder] shows when [value] is empty.
 */
@Composable
internal fun ComposerTitleField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    brand: Color,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(brand),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
    )
    HorizontalDivider(
        thickness = if (focused) 2.dp else 1.dp,
        color = if (focused) brand else MaterialTheme.colorScheme.outlineVariant,
    )
}

/** A flat, icon-led row: leading [icon] + [content] + optional [trailing]. */
@Composable
internal fun ComposerRow(
    icon: ImageVector,
    filled: Boolean,
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = verticalAlignment,
        horizontalArrangement = Arrangement.spacedBy(ComposerRowIconGap),
    ) {
        Icon(
            imageVector = icon,
            // Decorative — the adjacent text/field conveys the row's meaning.
            contentDescription = null,
            tint = if (filled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
        trailing?.invoke()
    }
}

/** Inline, borderless editable text used inside a [ComposerRow] (no box chrome). */
@Composable
internal fun RowScope.ComposerEditableField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    cursorColor: Color,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(cursorColor),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.weight(1f),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
    )
}
