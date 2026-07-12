package id.homebase.core.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// ponytail: filled in per-platform in later tasks; no-op keeps the spike desktop-only.
@Composable
actual fun Modifier.pasteImageContextMenuItem(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
): Modifier = this
