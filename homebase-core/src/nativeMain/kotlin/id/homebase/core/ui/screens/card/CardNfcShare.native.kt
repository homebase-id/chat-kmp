package id.homebase.core.ui.screens.card

import androidx.compose.runtime.Composable

private object IphoneCardNfc : CardNfc {
    override val canShare = false
    override val isEnabled = false
    override fun openSettings() = Unit
}

@Composable
actual fun rememberCardNfc(): CardNfc? = IphoneCardNfc

@Composable
actual fun CardTapShareDriver(onShared: () -> Unit) = Unit
