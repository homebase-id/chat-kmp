package id.homebase.core.notifications

internal enum class DesktopOs {
    Mac, Windows, Linux;

    companion object {
        fun current(osName: String = System.getProperty("os.name").orEmpty()): DesktopOs = when {
            osName.contains("Mac", ignoreCase = true) -> Mac
            osName.contains("Windows", ignoreCase = true) -> Windows
            else -> Linux
        }
    }
}

internal enum class ImageSource { Avatar, Photo }

internal data class NotificationImageSlots(val largeImage: ImageSource?, val smallIconIsAvatar: Boolean)

/**
 * Where Nucleus puts its two image fields per OS, and so what each one may carry:
 * - macOS: `largeImage` is the UNNotification attachment; the left icon is always the app icon
 *   (a sender avatar there needs communication notifications, which the JVM can't donate). The
 *   attachment is only ever the message's photo.
 * - Windows: `largeImage` is the toast's hero image (the photo); `smallIcon` is the
 *   app-logo override (the sender's avatar, pre-cropped to a circle).
 * - Linux: one `image-path` hint, shown as the notification's picture: the photo when there is
 *   one, otherwise the avatar.
 */
internal fun notificationImageSlots(os: DesktopOs, hasAvatar: Boolean, hasImage: Boolean): NotificationImageSlots {
    val photo = ImageSource.Photo.takeIf { hasImage }
    return when (os) {
        DesktopOs.Mac -> NotificationImageSlots(largeImage = photo, smallIconIsAvatar = false)
        DesktopOs.Windows -> NotificationImageSlots(largeImage = photo, smallIconIsAvatar = hasAvatar)
        DesktopOs.Linux -> NotificationImageSlots(
            largeImage = photo ?: ImageSource.Avatar.takeIf { hasAvatar },
            smallIconIsAvatar = false,
        )
    }
}
