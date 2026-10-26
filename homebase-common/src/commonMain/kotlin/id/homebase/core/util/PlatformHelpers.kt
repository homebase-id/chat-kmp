package id.homebase.core.util

expect fun isDesktop(): Boolean

expect fun isMobile(): Boolean

expect fun isWeb(): Boolean

expect fun isDesktopOrWeb(): Boolean

/** True on iOS (native) targets where ComposeUIViewController handles keyboard avoidance. */
expect fun isNativeMobile(): Boolean

/**
 * What the device can actually do for input, as seen right now.
 * [primaryTouch]: the primary pointer is coarse (finger). [anyTouch]: a touch input is available.
 * [anyHover]: some available input can hover (mouse, trackpad).
 */
data class InputCapabilities(val primaryTouch: Boolean, val anyTouch: Boolean, val anyHover: Boolean)

/**
 * Native and desktop targets are fixed: Android/iOS are touch, JVM desktop is pointer.
 * Web is resolved from the browser on every call, so a phone browser is touch, a desktop browser is
 * pointer, and a hybrid device reports both [InputCapabilities.anyTouch] and [InputCapabilities.anyHover].
 */
expect fun inputCapabilities(): InputCapabilities

/** The one layout switch: the primary input is a finger. Complement of [usesPointerUx]. */
fun usesTouchUx(): Boolean = inputCapabilities().primaryTouch

/** The primary input is a mouse or trackpad. */
fun usesPointerUx(): Boolean = !usesTouchUx()

/** Long-press / swipe message gestures apply; true alongside [supportsHoverActions] on hybrid devices. */
fun supportsTouchGestures(): Boolean = inputCapabilities().let { it.primaryTouch || it.anyTouch }

/** Hover affordances (action icons on hover) apply; true alongside [supportsTouchGestures] on hybrid devices. */
fun supportsHoverActions(): Boolean = inputCapabilities().let { !it.primaryTouch || it.anyHover }
