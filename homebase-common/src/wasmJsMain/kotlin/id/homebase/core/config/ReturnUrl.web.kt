package id.homebase.core.config

import kotlinx.browser.document

// Browsers drop custom-scheme redirects; index.html intercepts these paths.
actual fun returnUrl(): String = document.baseURI + "permission-callback"

actual fun dataUpgradeReturnUrl(): String = document.baseURI + "data-upgrade-callback"

actual fun createAccountReturnUrl(): String? = null
