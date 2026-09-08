package id.homebase.chat.services

import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor

const val HLS_PLAYLIST_CONTENT_TYPE = "application/vnd.apple.mpegurl"

/**
 * Whether one payload of one chat message should be downloaded and written to the device album.
 * Pure so the policy can be tested without Compose, a database or a network.
 */
fun shouldAutoSave(
    payload: PayloadDescriptor,
    messageDataType: Int?,
    isIncoming: Boolean,
    isSoftDeleted: Boolean,
    autoSaveEnabled: Boolean,
    unmeteredOnly: Boolean,
    isUnmetered: Boolean,
    alreadySaved: Boolean,
    messageTimestampMs: Long,
    enabledSinceMs: Long,
): Boolean {
    if (!autoSaveEnabled) return false
    if (unmeteredOnly && !isUnmetered) return false
    if (!isIncoming || isSoftDeleted || alreadySaved) return false
    // Only what arrived after the switch was flipped: the recent-message window the sync lane
    // rescans still holds older media the user never asked to have copied into their album.
    if (messageTimestampMs < enabledSinceMs) return false
    // Only plain messages carry user attachments. An Event cover photo (210) and a shared
    // contact's photo (215) are keyed `chat_web0` too, so the payload key alone cannot tell
    // them from a first attachment.
    if ((messageDataType ?: 0) != 0) return false
    if (isNonMediaPayloadKey(payload.key)) return false

    val contentType = payload.contentType ?: return false
    return when {
        contentType.startsWith("image/") ->
            (payload.descriptorInfo() as? DescriptorContent.ImageFile)?.isSticker != true

        // ponytail: segmented video is skipped rather than remuxed. Saving it needs
        // MediaDownloadHandler.downloadAndRemuxHlsToMp4, whose concurrent ffmpeg_execute calls
        // crash on iOS — put the remux behind a single-permit semaphore before enabling it here.
        contentType == HLS_PLAYLIST_CONTENT_TYPE -> false

        contentType.startsWith("video/") ->
            (payload.descriptorInfo() as? DescriptorContent.VideoFile)?.isSegmented != true

        else -> false
    }
}

/**
 * Payload keys that carry app plumbing rather than a photo the user would want in their album:
 * the message body overflow (`dflt_key`), link and location previews (both of which carry an
 * image content type), and the two descriptor slots. Matched exactly, as every other chat media
 * filter does — user attachments are keyed `chat_web<index>`, and a prefix match on `chat_web`
 * would swallow all of them.
 */
private fun isNonMediaPayloadKey(key: String): Boolean =
    key == ChatProtocol.DefaultPayloadKey ||
        key == ChatProtocol.PAYLOAD_KEY_LINKS ||
        key == ChatProtocol.PAYLOAD_KEY_LOCATION ||
        key == ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB ||
        key.startsWith(ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY)
