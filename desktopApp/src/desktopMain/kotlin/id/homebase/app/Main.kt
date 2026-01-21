package id.homebase.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import id.homebase.core.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Homebase Chat",
        state = WindowState(size = DpSize(800.dp, 900.dp))
    ) {
        App()
    }
}
