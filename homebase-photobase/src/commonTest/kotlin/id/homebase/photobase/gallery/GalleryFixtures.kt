package id.homebase.photobase.gallery

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import kotlin.uuid.Uuid

internal fun galleryItem() = PhotoGalleryItem(Uuid.random(), Uuid.random(), 0, KeyHeader.newRandom16(), PayloadDescriptor(key = "photo_main", contentType = "image/jpeg"))

// Days 30, 20, 10 with 2, 3 and 1 photos.
internal val threeDays = LibraryDays(intArrayOf(30, 20, 10), intArrayOf(2, 3, 1))
