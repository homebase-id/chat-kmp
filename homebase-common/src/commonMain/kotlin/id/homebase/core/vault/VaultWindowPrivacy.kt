package id.homebase.core.vault

/**
 * True where the OS cannot blank the app in the task switcher, so Compose must draw its own privacy cover while the app is hidden.
 * True on desktop only; the other platforms never need the overlay.
 */
expect val needsComposePrivacyOverlay: Boolean
