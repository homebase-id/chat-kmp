package id.homebase.chat.viewonce

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector

val ViewOnceIcon: ImageVector by lazy {
    materialIcon(name = "ViewOnce") {
        materialPath(pathFillType = PathFillType.EvenOdd) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveTo(6.48f, 22f, 12f, 22f)
            reflectiveCurveTo(22f, 17.52f, 22f, 12f)
            reflectiveCurveTo(17.52f, 2f, 12f, 2f)
            close()
            moveTo(12f, 20f)
            curveTo(7.59f, 20f, 4f, 16.41f, 4f, 12f)
            reflectiveCurveTo(7.59f, 4f, 12f, 4f)
            reflectiveCurveTo(20f, 7.59f, 20f, 12f)
            reflectiveCurveTo(16.41f, 20f, 12f, 20f)
            close()
            moveTo(10.5f, 9.8f)
            lineTo(12.5f, 8.3f)
            lineTo(13.5f, 8.3f)
            lineTo(13.5f, 16f)
            lineTo(12f, 16f)
            lineTo(12f, 10.2f)
            lineTo(10.5f, 11.3f)
            close()
        }
    }
}
