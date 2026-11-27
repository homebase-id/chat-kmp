package id.homebase.api.video

import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.file.CONTENT_TYPE_MP4
import id.homebase.api.util.safeFileName
import id.homebase.api.util.splitExtension

/** An HLS video's payload is an MPEG-TS stream, not a file people can open: it leaves the app through [id.homebase.api.client.payload.PayloadReader.downloadHlsAsMp4]. */
val PayloadDescriptor.isHlsVideo: Boolean
    get() = (descriptorInfo() as? DescriptorContent.VideoFile)?.isSegmented == true

/** The type of the file a person gets for this payload: an MP4 for an HLS video. */
val PayloadDescriptor.playableContentType: String?
    get() = if (isHlsVideo) CONTENT_TYPE_MP4 else contentType

/** [name] for the file a person gets for this payload: an HLS video's is an `.mp4`. */
fun PayloadDescriptor.playableFileName(name: String?): String? =
    if (isHlsVideo) safeFileName(name?.let { splitExtension(it).first }, "video", "mp4") else name
