package id.homebase.core.util

import id.homebase.core.MainViewControllerRef
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

private const val KEY_WINDOW_POLL_NS = 50_000_000L
private const val KEY_WINDOW_MAX_POLLS = 20

// keyWindow is a Compose Popup's own window while a DropdownMenu is open; anything presented on it dies with the popup.
internal fun topViewController(): UIViewController? {
    var presenter: UIViewController =
        MainViewControllerRef.instance?.view?.window?.rootViewController
            ?: UIApplication.sharedApplication.connectedScenes
                .filterIsInstance<UIWindowScene>()
                .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
                .firstOrNull { it.isKeyWindow() && it.rootViewController != null }
                ?.rootViewController
            ?: return null
    while (true) {
        val next = presenter.presentedViewController ?: break
        if (next.isBeingDismissed()) break
        presenter = next
    }
    return presenter
}

// A remote view controller binds to the key window at present time; presenting while a Popup window is key dies with it.
internal fun presentWhenMainWindowIsKey(attempt: Int = 0, present: (UIViewController) -> Unit) {
    val mainWindow = MainViewControllerRef.instance?.view?.window
    if (mainWindow != null && !mainWindow.isKeyWindow() && attempt < KEY_WINDOW_MAX_POLLS) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, KEY_WINDOW_POLL_NS), dispatch_get_main_queue()) {
            presentWhenMainWindowIsKey(attempt + 1, present)
        }
        return
    }
    topViewController()?.let(present)
}
