package id.homebase.core.location.tracking

/**
 * Human-readable name for THIS device, used when the Location add-on writes its
 * device-profile file to the drive (auto-only naming v1 — no rename UI yet).
 * Platform-derived: e.g. "Google Pixel 9", "iPhone", "Linux Desktop".
 */
expect fun deviceDisplayName(): String

/** Coarse platform tag stored in the device profile ("android" | "ios" | "desktop" | "web"). */
expect fun devicePlatform(): String
