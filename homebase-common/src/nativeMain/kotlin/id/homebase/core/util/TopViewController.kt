package id.homebase.core.util

import id.homebase.core.MainViewControllerRef
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

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
