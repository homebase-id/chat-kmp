package id.homebase.api.util

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCanonicalMapping

@OptIn(BetaInteropApi::class)
actual fun String.toNfc(): String = NSString.create(string = this).precomposedStringWithCanonicalMapping
