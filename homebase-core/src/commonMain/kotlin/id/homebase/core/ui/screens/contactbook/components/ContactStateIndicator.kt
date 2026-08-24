package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.util.truncateToCodePoints
import id.homebase.core.ui.screens.contactbook.ContactState
import id.homebase.resources.MR
import id.homebase.resources.contact_circle_pending
import id.homebase.resources.contact_circles_more
import id.homebase.resources.contact_state_chat_cd
import id.homebase.resources.contact_state_circle_cd
import id.homebase.resources.contact_state_new_cd
import org.jetbrains.compose.resources.stringResource

/**
 * The row's fixed trailing state slot. Monochrome vector icons, not the emoji from the docs:
 * color-font emoji ignore `tint`, so they cannot be rendered in the theme's primary colour.
 */
@Composable
fun ContactStateIcon(state: ContactState, modifier: Modifier = Modifier) {
    val (icon, description) = when (state) {
        ContactState.New -> Icons.Filled.WavingHand to stringResource(MR.string.contact_state_new_cd)
        ContactState.Chat -> Icons.Outlined.ChatBubbleOutline to stringResource(MR.string.contact_state_chat_cd)
        ContactState.Circle -> Icons.Outlined.Circle to stringResource(MR.string.contact_state_circle_cd)
    }
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(18.dp),
    )
}

private const val MAX_VISIBLE_CIRCLES = 3

/**
 * The circle line under a Circle contact's name. User-chosen emoji render as full-colour emoji —
 * a deliberately different visual species from the monochrome state icon, so a user who picks 💬
 * for a circle creates no ambiguity.
 */
@Composable
fun ContactCircleRow(
    circles: List<RedactedCircleDefinition>,
    pendingCircles: List<RedactedCircleDefinition> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (circles.isEmpty() && pendingCircles.isEmpty()) return

    val shown = (circles + pendingCircles).take(MAX_VISIBLE_CIRCLES)
    val overflow = circles.size + pendingCircles.size - shown.size

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shown.forEach { circle ->
            val pending = circle in pendingCircles
            val label = if (pending) {
                stringResource(MR.string.contact_circle_pending, circle.name)
            } else {
                circle.name
            }
            CirclePill(circle = circle, label = label, pending = pending)
        }
        if (overflow > 0) {
            Text(
                text = stringResource(MR.string.contact_circles_more, overflow),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CirclePill(
    circle: RedactedCircleDefinition,
    label: String,
    pending: Boolean,
) {
    val emoji = circle.emoji?.takeIf { it.isNotBlank() }
    val modifier = Modifier
        .semantics { contentDescription = label }
        .alpha(if (pending) 0.5f else 1f)

    if (emoji != null) {
        Text(
            text = emoji,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
    } else {
        Text(
            text = circle.name.abbreviateCircleName(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

private const val ABBREVIATION_THRESHOLD = 6

/**
 * Vowel-dropped shortening (Family → fmly). Latin-script only — for anything else the vowel set
 * is meaningless, so fall back to a codepoint-safe truncation rather than mangling the name.
 * The full name is always the accessibility label, never this.
 */
internal fun String.abbreviateCircleName(): String {
    if (length <= ABBREVIATION_THRESHOLD) return this
    if (none { it in 'a'..'z' || it in 'A'..'Z' }) return truncateToCodePoints(ABBREVIATION_THRESHOLD)

    val vowels = "aeiouAEIOU"
    val shortened = buildString {
        this@abbreviateCircleName.forEachIndexed { index, c ->
            if (index == 0 || c !in vowels) append(c)
        }
    }
    return (if (shortened.length in 1..ABBREVIATION_THRESHOLD) shortened else shortened.truncateToCodePoints(ABBREVIATION_THRESHOLD))
        .lowercase()
}
