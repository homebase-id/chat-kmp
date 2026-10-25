package id.homebase.chat.viewonce

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private fun PathBuilder.digitOne() {
    moveTo(12.6f, 7f)
    lineTo(14f, 7f)
    lineTo(14f, 17f)
    lineTo(12f, 17f)
    lineTo(12f, 9.6f)
    lineTo(10f, 10.8f)
    lineTo(10f, 8.7f)
    close()
}

private fun PathBuilder.checkMark() {
    moveTo(7.4f, 12.2f)
    lineTo(8.6f, 11f)
    lineTo(10.6f, 13f)
    lineTo(15.4f, 8.2f)
    lineTo(16.6f, 9.4f)
    lineTo(10.6f, 15.4f)
    close()
}

private fun PathBuilder.dashedRing(outer: Float, inner: Float, dashes: Int, gapDegrees: Float) {
    val step = 360f / dashes
    fun point(radius: Float, degrees: Float): Pair<Float, Float> {
        val radians = (degrees - 90f) * PI.toFloat() / 180f
        return 12f + radius * cos(radians) to 12f + radius * sin(radians)
    }
    repeat(dashes) { i ->
        val start = i * step + gapDegrees / 2f
        val end = (i + 1) * step - gapDegrees / 2f
        val (osx, osy) = point(outer, start)
        val (oex, oey) = point(outer, end)
        val (iex, iey) = point(inner, end)
        val (isx, isy) = point(inner, start)
        moveTo(osx, osy)
        arcTo(outer, outer, 0f, false, true, oex, oey)
        lineTo(iex, iey)
        arcTo(inner, inner, 0f, false, false, isx, isy)
        close()
    }
}

val ViewOnceIcon: ImageVector by lazy {
    materialIcon(name = "ViewOnce") {
        materialPath {
            dashedRing(outer = 10.5f, inner = 8.5f, dashes = 10, gapDegrees = 12f)
            digitOne()
        }
    }
}

val ViewOnceRingIcon: ImageVector by lazy {
    materialIcon(name = "ViewOnceRing") {
        materialPath { dashedRing(outer = 10.5f, inner = 8.5f, dashes = 10, gapDegrees = 12f) }
    }
}

val ViewOnceOpenedIcon: ImageVector by lazy {
    materialIcon(name = "ViewOnceOpened") {
        materialPath {
            dashedRing(outer = 10.5f, inner = 8.5f, dashes = 10, gapDegrees = 12f)
            checkMark()
        }
    }
}
