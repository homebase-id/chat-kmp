package id.homebase.core.ui.screens.filesync.components

import kotlinx.browser.window

internal actual fun filesyncInstallHere(): FilesyncInstallHere =
    filesyncInstallForBrowser(window.navigator.userAgent, maxTouchPoints())

private fun maxTouchPoints(): Int = js("navigator.maxTouchPoints || 0")
