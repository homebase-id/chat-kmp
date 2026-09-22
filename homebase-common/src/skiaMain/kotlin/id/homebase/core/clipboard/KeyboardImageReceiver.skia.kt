package id.homebase.core.clipboard

import androidx.compose.runtime.Composable

@Composable
actual fun KeyboardImageReceiver(
    onImageReceived: ((ByteArray) -> Unit)?,
    content: @Composable () -> Unit,
) = content()
