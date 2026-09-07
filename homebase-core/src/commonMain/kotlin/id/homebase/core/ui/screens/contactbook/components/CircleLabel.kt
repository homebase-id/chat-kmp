package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * A circle's optional emoji followed by its name. The emoji is full-colour by design — it is the
 * user's own choice and the one place in the contact book that isn't tinted — so it carries no
 * colour of its own and must never be given one.
 *
 * Cleared from the semantics tree: screen readers pronounce ZWJ sequences unpredictably, so the
 * name beside it is the whole accessible label.
 */
@Composable
fun CircleLabel(
    emoji: String?,
    name: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!emoji.isNullOrBlank()) {
            Text(
                text = emoji,
                style = style,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        Text(text = name, style = style)
    }
}
