package id.homebase.chat.calling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.call_verification_body
import id.homebase.resources.call_verification_code_label
import id.homebase.resources.call_verification_compare
import id.homebase.resources.call_verification_mismatch
import id.homebase.resources.call_verification_title
import id.homebase.resources.close
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallVerificationSheet(code: String, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, sheetMaxWidth = 480.dp) {
        CallVerificationContent(code, onClose = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } })
    }
}

@Composable
internal fun CallVerificationContent(code: String, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Default.VerifiedUser, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(MR.string.call_verification_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(MR.string.call_verification_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 20.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(MR.string.call_verification_code_label), style = MaterialTheme.typography.labelLarge)
                Text(code, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold).tabular())
            }
        }
        VerificationHint(Icons.Outlined.RecordVoiceOver, stringResource(MR.string.call_verification_compare))
        VerificationHint(Icons.Outlined.Info, stringResource(MR.string.call_verification_mismatch))
        FilledTonalButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(MR.string.close))
        }
    }
}

@Composable
private fun VerificationHint(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
