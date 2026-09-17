package id.homebase.chat.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.vector.ImageVector
import id.homebase.core.ui.assets.Apk
import id.homebase.core.ui.assets.Excel
import id.homebase.core.ui.assets.File
import id.homebase.core.ui.assets.FileCode
import id.homebase.core.ui.assets.FileZip
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.Pdf
import id.homebase.core.ui.assets.WordFile
import id.homebase.core.util.FileKind

internal val FileKind.icon: ImageVector
    get() = when (this) {
        FileKind.Archive -> HomebaseIcons.FileZip
        FileKind.Pdf -> HomebaseIcons.Pdf
        FileKind.Word -> HomebaseIcons.WordFile
        FileKind.Spreadsheet -> HomebaseIcons.Excel
        FileKind.Presentation -> Icons.Filled.Slideshow
        FileKind.Text -> Icons.Filled.Description
        FileKind.Code -> HomebaseIcons.FileCode
        FileKind.Audio -> Icons.Filled.AudioFile
        FileKind.Video -> Icons.Filled.VideoFile
        FileKind.Image -> Icons.Filled.Image
        FileKind.Apk -> HomebaseIcons.Apk
        FileKind.Generic -> HomebaseIcons.File
    }
