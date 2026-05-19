package id.homebase.core.pdf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import id.homebase.resources.MR
import id.homebase.resources.vault_pdf_not_supported
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun PdfPageViewer(
    filePath: String,
    onTap: (() -> Unit)?,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(MR.string.vault_pdf_not_supported),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
