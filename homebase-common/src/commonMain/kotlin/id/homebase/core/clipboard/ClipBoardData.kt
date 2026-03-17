package id.homebase.core.clipboard

import androidx.compose.ui.platform.ClipEntry

expect fun clipEntryOf(string: String): ClipEntry
