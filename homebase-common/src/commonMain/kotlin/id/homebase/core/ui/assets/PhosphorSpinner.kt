package id.homebase.core.ui.assets/*
MIT License

Copyright (c) 2020 Phosphor Icons

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

*/
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val HomebaseIcons.PhosphorSpinner: ImageVector
    get() {
        if (_PhosphorSpinner != null) return _PhosphorSpinner!!
        
        _PhosphorSpinner = ImageVector.Builder(
            name = "spinner",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 256f,
            viewportHeight = 256f
        ).apply {
            path(
                fill = SolidColor(Color.Black)
            ) {
                moveTo(136f, 32f)
                verticalLineTo(64f)
                arcToRelative(8f, 8f, 0f, false, true, -16f, 0f)
                verticalLineTo(32f)
                arcToRelative(8f, 8f, 0f, false, true, 16f, 0f)
                close()
                moveToRelative(37.25f, 58.75f)
                arcToRelative(8f, 8f, 0f, false, false, 5.66f, -2.35f)
                lineToRelative(22.63f, -22.62f)
                arcToRelative(8f, 8f, 0f, false, false, -11.32f, -11.32f)
                lineTo(167.6f, 77.09f)
                arcToRelative(8f, 8f, 0f, false, false, 5.65f, 13.66f)
                close()
                moveTo(224f, 120f)
                horizontalLineTo(192f)
                arcToRelative(8f, 8f, 0f, false, false, 0f, 16f)
                horizontalLineToRelative(32f)
                arcToRelative(8f, 8f, 0f, false, false, 0f, -16f)
                close()
                moveToRelative(-45.09f, 47.6f)
                arcToRelative(8f, 8f, 0f, false, false, -11.31f, 11.31f)
                lineToRelative(22.62f, 22.63f)
                arcToRelative(8f, 8f, 0f, false, false, 11.32f, -11.32f)
                close()
                moveTo(128f, 184f)
                arcToRelative(8f, 8f, 0f, false, false, -8f, 8f)
                verticalLineToRelative(32f)
                arcToRelative(8f, 8f, 0f, false, false, 16f, 0f)
                verticalLineTo(192f)
                arcTo(8f, 8f, 0f, false, false, 128f, 184f)
                close()
                moveTo(77.09f, 167.6f)
                lineTo(54.46f, 190.22f)
                arcToRelative(8f, 8f, 0f, false, false, 11.32f, 11.32f)
                lineTo(88.4f, 178.91f)
                arcTo(8f, 8f, 0f, false, false, 77.09f, 167.6f)
                close()
                moveTo(72f, 128f)
                arcToRelative(8f, 8f, 0f, false, false, -8f, -8f)
                horizontalLineTo(32f)
                arcToRelative(8f, 8f, 0f, false, false, 0f, 16f)
                horizontalLineTo(64f)
                arcTo(8f, 8f, 0f, false, false, 72f, 128f)
                close()
                moveTo(65.78f, 54.46f)
                arcTo(8f, 8f, 0f, false, false, 54.46f, 65.78f)
                lineTo(77.09f, 88.4f)
                arcTo(8f, 8f, 0f, false, false, 88.4f, 77.09f)
                close()
            }
        }.build()
        
        return _PhosphorSpinner!!
    }

private var _PhosphorSpinner: ImageVector? = null

