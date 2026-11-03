package id.homebase.core.ui.assets/*
The MIT License (MIT)

Copyright (c) 2019-2024 The Bootstrap Authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

*/
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val BootstrapChat: ImageVector
    get() {
        if (_BootstrapChat != null) return _BootstrapChat!!
        
        _BootstrapChat = ImageVector.Builder(
            name = "chat",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 16f,
            viewportHeight = 16f
        ).apply {
            path(
                fill = SolidColor(Color.Black)
            ) {
                moveTo(8f, 15f)
                curveToRelative(4.418f, 0f, 8f, -3.134f, 8f, -7f)
                reflectiveCurveToRelative(-3.582f, -7f, -8f, -7f)
                reflectiveCurveToRelative(-8f, 3.134f, -8f, 7f)
                curveToRelative(0f, 1.76f, 0.743f, 3.37f, 1.97f, 4.6f)
                curveToRelative(-0.097f, 1.016f, -0.417f, 2.13f, -0.771f, 2.966f)
                curveToRelative(-0.079f, 0.186f, 0.074f, 0.394f, 0.273f, 0.362f)
                curveToRelative(2.256f, -0.37f, 3.597f, -0.938f, 4.18f, -1.234f)
                arcTo(9f, 9f, 0f, false, false, 8f, 15f)
            }
        }.build()
        
        return _BootstrapChat!!
    }

private var _BootstrapChat: ImageVector? = null

private const val ChatOutlinePath = "M2.678 11.894a1 1 0 0 1 .287.801 11 11 0 0 1-.398 2c1.395-.323 2.247-.697 2.634-.893a1 1 0 0 1 .71-.074A8 8 0 0 0 8 14c3.996 0 7-2.807 7-6s-3.004-6-7-6-7 2.808-7 6c0 1.468.617 2.83 1.678 3.894m-.493 3.905a22 22 0 0 1-.713.129c-.2.032-.352-.176-.273-.362a10 10 0 0 0 .244-.637l.003-.01c.248-.72.45-1.548.524-2.319C.743 11.37 0 9.76 0 8c0-3.866 3.582-7 8-7s8 3.134 8 7-3.582 7-8 7a9 9 0 0 1-2.347-.306c-.52.263-1.639.742-3.468 1.105"

val BootstrapChatOutlined: ImageVector
    get() {
        _BootstrapChatOutlined?.let { return it }
        return ImageVector.Builder(
            name = "chatOutlined",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 16f,
            viewportHeight = 16f,
        ).apply {
            addPath(pathData = addPathNodes(ChatOutlinePath), fill = SolidColor(Color.Black))
        }.build().also { _BootstrapChatOutlined = it }
    }

private var _BootstrapChatOutlined: ImageVector? = null
