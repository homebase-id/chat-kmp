package id.homebase.core.util

import platform.Foundation.NSURL
import platform.SafariServices.SFSafariViewController
import platform.UIKit.UIApplication

actual object InAppBrowser {
    actual fun open(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        var presenter = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return
        // Present from the topmost VC so an already-presented sheet doesn't swallow the call.
        while (presenter.presentedViewController != null) {
            presenter = presenter.presentedViewController!!
        }
        presenter.presentViewController(
            SFSafariViewController(uRL = nsUrl),
            animated = true,
            completion = null,
        )
    }
}
