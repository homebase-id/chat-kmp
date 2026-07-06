package id.homebase.core.location.tracking

import platform.UIKit.UIDevice

actual fun deviceDisplayName(): String {
    // UIDevice.name returns a generic value on iOS 16+ without the
    // user-assigned-device-name entitlement; model ("iPhone"/"iPad") is the
    // honest v1 default.
    return UIDevice.currentDevice.model.ifBlank { "iPhone" }
}

actual fun devicePlatform(): String = "ios"
