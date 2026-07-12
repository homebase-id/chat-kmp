package id.homebase.core.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// iOS deliberately held pending discussion (#1046): no-op = the "Paste image" item never
// shows on iOS. readClipboardImage()'s iOS actual already reads UIPasteboard, so enabling this
// later is a one-line change to the skiko item() (same as jvm/web).
@Composable
actual fun Modifier.pasteImageContextMenuItem(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
): Modifier = this
