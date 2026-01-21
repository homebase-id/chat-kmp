package id.homebase.core

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

object MainViewControllerRef {
    lateinit var instance: UIViewController
}

fun MainViewController(): UIViewController {
    val controller = ComposeUIViewController { KoinApp() }
    MainViewControllerRef.instance = controller
    return controller
}

