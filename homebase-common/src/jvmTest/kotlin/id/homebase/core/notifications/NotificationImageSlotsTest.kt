package id.homebase.core.notifications

import id.homebase.core.util.HostOs
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NotificationImageSlotsTest {

    @Test
    fun macOsNeverPutsTheAvatarInTheAttachment() {
        assertEquals(NotificationImageSlots(null, false), notificationImageSlots(HostOs.Mac, hasAvatar = true, hasImage = false))
        assertEquals(NotificationImageSlots(ImageSource.Photo, false), notificationImageSlots(HostOs.Mac, hasAvatar = true, hasImage = true))
    }

    @Test
    fun windowsUsesTheAvatarAsAppLogoAndThePhotoAsHero() {
        assertEquals(NotificationImageSlots(null, true), notificationImageSlots(HostOs.Windows, hasAvatar = true, hasImage = false))
        assertEquals(NotificationImageSlots(ImageSource.Photo, true), notificationImageSlots(HostOs.Windows, hasAvatar = true, hasImage = true))
        assertEquals(NotificationImageSlots(ImageSource.Photo, false), notificationImageSlots(HostOs.Windows, hasAvatar = false, hasImage = true))
    }

    @Test
    fun linuxShowsThePhotoOrElseTheAvatar() {
        assertEquals(NotificationImageSlots(ImageSource.Avatar, false), notificationImageSlots(HostOs.Linux, hasAvatar = true, hasImage = false))
        assertEquals(NotificationImageSlots(ImageSource.Photo, false), notificationImageSlots(HostOs.Linux, hasAvatar = true, hasImage = true))
        assertEquals(NotificationImageSlots(null, false), notificationImageSlots(HostOs.Linux, hasAvatar = false, hasImage = false))
    }

    @Test
    fun webpIsReencodedAsPngAndTheCircleCropClearsTheCorners() {
        val surface = Surface.makeRasterN32Premul(40, 20)
        surface.canvas.clear(Color.RED)
        val webp = assertNotNull(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.WEBP)).bytes

        val png = assertNotNull(encodePng(webp, circle = false))
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), png.take(4).map { it.toInt() and 0xFF })

        val circle = Image.makeFromEncoded(assertNotNull(encodePng(webp, circle = true)))
        assertEquals(20, circle.width)
        assertEquals(20, circle.height)
        val pixels = Bitmap.makeFromImage(circle)
        assertEquals(0, Color.getA(pixels.getColor(0, 0)))
        assertEquals(255, Color.getA(pixels.getColor(10, 10)))
    }

    @Test
    fun undecodableBytesGiveNoImage() {
        assertNull(encodePng(byteArrayOf(1, 2, 3), circle = false))
    }
}
