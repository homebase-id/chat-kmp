package id.homebase.api.util

import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCanonicalMapping

actual fun String.toNfc(): String = NSString.create(string = this).precomposedStringWithCanonicalMapping
