package id.homebase.app.id.homebase.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToBrowserNavigation
import id.homebase.core.App

@OptIn(ExperimentalComposeUiApi::class)
@ExperimentalBrowserHistoryApi
fun main() {
    ComposeViewport("ComposeApp") {
        App(
            onNavHostReady = { it.bindToBrowserNavigation() }
        )
    }
}