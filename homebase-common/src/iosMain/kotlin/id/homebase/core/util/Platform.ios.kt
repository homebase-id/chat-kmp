
package id.homebase.core.util

import platform.UIKit.UIDevice

actual object Platform {
    actual val osName: String = UIDevice.currentDevice.systemName
    actual val osVersion: String = UIDevice.currentDevice.systemVersion
}
