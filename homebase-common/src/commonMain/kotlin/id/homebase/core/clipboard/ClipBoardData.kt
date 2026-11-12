package id.homebase.core.clipboard

import androidx.compose.ui.platform.ClipEntry

/**
 * A plain-text [ClipEntry] holding [string] verbatim (no trimming, empty allowed).
 * [sensitive] asks the OS to hide the content from clipboard previews; only Android 13+ honours it, every other platform ignores it.
 */
expect fun clipEntryOf(string: String, sensitive: Boolean = false): ClipEntry
