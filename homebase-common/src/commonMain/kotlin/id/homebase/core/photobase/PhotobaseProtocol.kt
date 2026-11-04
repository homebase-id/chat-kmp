package id.homebase.core.photobase

import kotlin.uuid.Uuid

/** The Photos drive format. */
object PhotobaseProtocol {
    // Minted for this app. The drive address is permanent once anything has been backed up to it.
    const val AppId = "704334ede383476bb82e2b0d5bab36fd"
    val DriveAlias: Uuid = Uuid.parse("e3e5d4ed-462a-4fc0-835c-2d3722820be8")
    val DriveType: Uuid = Uuid.parse("764a8e3a-13b9-46e1-b53f-092679dc9953")
    const val AppSlug = "photobase"
    const val DriveSlug = "photos"
    const val DriveTypeSlug = "photos"
    const val CircleSlug = "photobase"

    const val PhotoFileType = 7201

    const val ImageDataType = 1
    const val VideoDataType = 2
    const val LivePhotoDataType = 3

    const val ImagePayloadKey = "photo_main"
    const val VideoPayloadKey = "video_main"
    const val LivePhotoVideoPayloadKey = "photo_live"

    /** Reconcile key of the identity's one recurring silent backup push. */
    val BackupPushTagId: Uuid = Uuid.parse("2e3f45a8-18b1-43fe-8f4b-19451525a5e7")
    val BackupPushTypeId: Uuid = Uuid.parse("6c1ed7ec-9d7c-447e-832a-137cf4d1539d")
}
