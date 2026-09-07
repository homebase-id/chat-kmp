package id.homebase.chat.services

import id.homebase.api.client.drives.files.PayloadDescriptor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatMediaAutoSaveRulesTest {

    private val photo = PayloadDescriptor(key = "chat_img0", contentType = "image/jpeg")

    private fun decide(
        payload: PayloadDescriptor = photo,
        isIncoming: Boolean = true,
        isSoftDeleted: Boolean = false,
        autoSaveEnabled: Boolean = true,
        unmeteredOnly: Boolean = true,
        isUnmetered: Boolean = true,
        alreadySaved: Boolean = false,
        messageTimestampMs: Long = 2_000L,
        enabledSinceMs: Long = 1_000L,
    ) = shouldAutoSave(
        payload = payload,
        isIncoming = isIncoming,
        isSoftDeleted = isSoftDeleted,
        autoSaveEnabled = autoSaveEnabled,
        unmeteredOnly = unmeteredOnly,
        isUnmetered = isUnmetered,
        alreadySaved = alreadySaved,
        messageTimestampMs = messageTimestampMs,
        enabledSinceMs = enabledSinceMs,
    )

    @Test
    fun incomingPhotoIsSaved() = assertTrue(decide())

    @Test
    fun incomingVideoIsSaved() =
        assertTrue(decide(payload = PayloadDescriptor(key = "chat_vid0", contentType = "video/mp4")))

    @Test
    fun settingOffSavesNothing() = assertFalse(decide(autoSaveEnabled = false))

    @Test
    fun nonMediaContentTypeIsSkipped() =
        assertFalse(decide(payload = PayloadDescriptor(key = "chat_doc0", contentType = "application/pdf")))

    @Test
    fun missingContentTypeIsSkipped() =
        assertFalse(decide(payload = PayloadDescriptor(key = "chat_img0", contentType = null)))

    @Test
    fun messageBodyOverflowIsSkipped() =
        assertFalse(decide(payload = PayloadDescriptor(key = "chat_web0", contentType = "image/jpeg")))

    @Test
    fun linkPreviewIsSkipped() =
        assertFalse(decide(payload = PayloadDescriptor(key = "chat_links", contentType = "image/jpeg")))

    @Test
    fun locationPreviewIsSkipped() =
        assertFalse(decide(payload = PayloadDescriptor(key = "chat_loc", contentType = "image/jpeg")))

    @Test
    fun defaultPayloadKeyIsSkipped() =
        assertFalse(decide(payload = PayloadDescriptor(key = "dflt_key", contentType = "image/jpeg")))

    @Test
    fun payloadDescriptorKeyIsSkipped() =
        assertFalse(decide(payload = PayloadDescriptor(key = "pld_desc0", contentType = "image/jpeg")))

    @Test
    fun stickerIsSkipped() = assertFalse(
        decide(
            payload = PayloadDescriptor(
                key = "chat_img0",
                contentType = "image/png",
                descriptorContent = """{"isSticker":true}""",
            )
        )
    )

    @Test
    fun ownOutgoingMessageIsSkipped() = assertFalse(decide(isIncoming = false))

    @Test
    fun softDeletedMessageIsSkipped() = assertFalse(decide(isSoftDeleted = true))

    @Test
    fun alreadySavedPayloadIsSkipped() = assertFalse(decide(alreadySaved = true))

    @Test
    fun hlsPlaylistIsSkipped() = assertFalse(
        decide(payload = PayloadDescriptor(key = "chat_vid0", contentType = HLS_PLAYLIST_CONTENT_TYPE))
    )

    @Test
    fun segmentedVideoIsSkipped() = assertFalse(
        decide(
            payload = PayloadDescriptor(
                key = "chat_vid0",
                contentType = "video/mp4",
                descriptorContent = """{"mimeType":"video/mp4","isSegmented":true}""",
            )
        )
    )

    @Test
    fun meteredNetworkIsSkippedWhileTheWifiGuardIsOn() =
        assertFalse(decide(unmeteredOnly = true, isUnmetered = false))

    @Test
    fun meteredNetworkIsSavedOnceTheWifiGuardIsOff() =
        assertTrue(decide(unmeteredOnly = false, isUnmetered = false))

    @Test
    fun mediaOlderThanTheSwitchIsNotBackfilled() =
        assertFalse(decide(messageTimestampMs = 999L, enabledSinceMs = 1_000L))

    @Test
    fun mediaAtTheMomentOfEnablingIsSaved() =
        assertTrue(decide(messageTimestampMs = 1_000L, enabledSinceMs = 1_000L))
}
