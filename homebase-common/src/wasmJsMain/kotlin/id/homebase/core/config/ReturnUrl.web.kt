package id.homebase.core.config

actual fun returnUrl(): String = "${AppConfig.DEEP_LINK_SCHEME}://permission-callback"

actual fun dataUpgradeReturnUrl(): String = "${AppConfig.DEEP_LINK_SCHEME}://data-upgrade-callback"
