package id.homebase.api.image

import org.jetbrains.skia.Image

/** [image] shrunk to [width]×[height] by the uploads' [scaleDown]: [image] itself at its own size, else a new image, leaving [image] open. */
suspend fun scaledForDisplay(image: Image, width: Int, height: Int): Image {
    val source = SkiaPipelineBitmap(image)
    val scaled = scaleDown(source, width, height)
    return if (scaled === source) image else (scaled as SkiaPipelineBitmap).image
}
