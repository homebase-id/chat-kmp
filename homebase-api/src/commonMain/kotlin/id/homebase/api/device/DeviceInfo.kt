package id.homebase.api.device

/**
 * Human-readable name for THIS device — the app-registration friendly name in the owner
 * console and the Location add-on's device profile. Auto-derived, no rename UI yet:
 * e.g. "Google Pixel 9", "iPhone", "Linux Desktop". Never blank.
 */
expect fun deviceDisplayName(): String

/** Coarse platform tag stored in the device profile ("android" | "ios" | "desktop" | "web"). */
expect fun devicePlatform(): String
