package id.homebase.core.scan

import androidx.compose.runtime.Composable

@Composable
actual fun rememberDocumentScanner(onPages: (List<ByteArray>) -> Unit): DocumentScanner? = null

actual suspend fun recognizeCardText(jpeg: ByteArray): String? = null
