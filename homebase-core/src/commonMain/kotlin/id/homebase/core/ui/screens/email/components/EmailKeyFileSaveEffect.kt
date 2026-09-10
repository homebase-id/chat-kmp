package id.homebase.core.ui.screens.email.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import id.homebase.core.localization.TranslationUtil
import id.homebase.core.ui.screens.email.secrets.EmailSecretsUiEvent
import id.homebase.core.ui.screens.email.secrets.EmailSecretsViewModel
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.email_secrets_key_save_failed
import id.homebase.resources.email_secrets_key_saved
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

/**
 * Hands a written private-key file to the platform's save flow and reports where it landed.
 *
 * The platform save cannot live in the ViewModel: FileSystemHandler comes from getUriHandler(), a
 * Composable accessor that is not in DI. The ViewModel writes the file and hands over the path;
 * this gives it to the OS and tells the ViewModel to drop the temp either way.
 */
@Composable
internal fun EmailKeyFileSaveEffect(
    viewModel: EmailSecretsViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val fileSystemHandler = getUriHandler()
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EmailSecretsUiEvent.SaveKeyFile -> fileSystemHandler.saveFile(
                    file = Path(event.path),
                    suggestedName = event.suggestedName,
                    onSuccess = { location ->
                        viewModel.discardKeyFile(event.path)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                TranslationUtil.getString(MR.string.email_secrets_key_saved, location)
                            )
                        }
                    },
                    onError = {
                        viewModel.discardKeyFile(event.path)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                TranslationUtil.getString(MR.string.email_secrets_key_save_failed)
                            )
                        }
                    },
                )

                EmailSecretsUiEvent.KeySaveFailed -> snackbarHostState.showSnackbar(
                    TranslationUtil.getString(MR.string.email_secrets_key_save_failed)
                )
            }
        }
    }
}
