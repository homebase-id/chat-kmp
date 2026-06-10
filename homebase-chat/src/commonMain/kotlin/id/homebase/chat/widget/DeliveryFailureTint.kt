package id.homebase.chat.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import id.homebase.core.ui.theme.HomebaseTheme

/**
 * The one source of truth for the delivery-failure accent ("orange"). The
 * bubble's status icon, Message Info's failure headlines, and per-recipient
 * error details all read this, so they can never drift apart again.
 */
@Composable
fun deliveryFailureTint(): Color = HomebaseTheme.extendedColors.warning
