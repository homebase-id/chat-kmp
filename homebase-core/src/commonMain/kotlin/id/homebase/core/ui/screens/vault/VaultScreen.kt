package id.homebase.core.ui.screens.vault

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import id.homebase.core.vault.BiometricResult
import id.homebase.core.vault.VaultPreferences
import id.homebase.core.vault.authenticateBiometric
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.vault_biometric_prompt_subtitle
import id.homebase.resources.vault_biometric_prompt_title
import id.homebase.resources.vault_label
import id.homebase.resources.vault_welcome
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    onNavigateBack: () -> Unit,
) {
    val vaultPreferences = koinInject<VaultPreferences>()
    val biometricTitle = stringResource(MR.string.vault_biometric_prompt_title)
    val biometricSubtitle = stringResource(MR.string.vault_biometric_prompt_subtitle)

    var authorized by remember { mutableStateOf(!vaultPreferences.biometricsEnabled.value) }

    LaunchedEffect(Unit) {
        if (authorized) return@LaunchedEffect
        when (authenticateBiometric(biometricTitle, biometricSubtitle)) {
            BiometricResult.Success, BiometricResult.Unavailable -> authorized = true
            BiometricResult.Failure -> onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.vault_label)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            if (authorized) {
                Text(
                    text = stringResource(MR.string.vault_welcome),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
    }
}
