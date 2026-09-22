package id.homebase.core.config

import kotlinx.browser.document

actual fun returnUrl(): String = "${AppConfig.DEEP_LINK_SCHEME}://permission-callback"

// Browsers drop custom-scheme redirects.
actual fun dataUpgradeReturnUrl(): String = document.baseURI

actual fun createAccountReturnUrl(): String? = null
