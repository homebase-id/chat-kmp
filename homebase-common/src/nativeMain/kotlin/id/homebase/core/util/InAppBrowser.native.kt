package id.homebase.core.util

import platform.Foundation.NSURL
import platform.SafariServices.SFSafariViewController

actual object InAppBrowser {
    actual fun open(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        val presenter = topViewController() ?: return
        presenter.presentViewController(
            SFSafariViewController(uRL = nsUrl),
            animated = true,
            completion = null,
        )
    }
}
