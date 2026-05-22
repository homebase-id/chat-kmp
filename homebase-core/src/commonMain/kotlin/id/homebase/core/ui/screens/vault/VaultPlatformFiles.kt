package id.homebase.core.ui.screens.vault

import io.github.vinceglb.filekit.PlatformFile

/**
 * Platform file helpers for the vault upload flow. filekit's `PlatformFile.path` and
 * `copyTo` (plus the path-string `PlatformFile(String)` constructor) exist on
 * android/jvm/apple but not on wasmJs — the browser has no filesystem paths. The web actuals
 * throw; vault file uploads are not supported on web yet.
 */

/** Absolute filesystem path of this file. */
expect val PlatformFile.pathCompat: String

/** Copy this file's contents to [destPath] on the local filesystem. */
expect suspend fun PlatformFile.copyToPath(destPath: String)
