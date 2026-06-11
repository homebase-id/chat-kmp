@file:OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)

package id.homebase.chat.services.sticker

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Unit tests for [HomebaseFile.toSavedSticker] and [SavedSticker.toImageData], plus the
 * [StickerProtocol] wire constants. These pin the sticker-library mapping contract:
 *  - a STICKER_FILE_TYPE file with a single image payload maps to a [SavedSticker],
 *  - the payload's `descriptorContent` carries the PR #664 `{"isSticker":true}` flag,
 *  - a payload-less file maps to null,
 *  - the reserved nullable groupId is read off the envelope (future packs),
 *  - [SavedSticker.toImageData] decodes the IV into a per-payload KeyHeader.
 */
class SavedStickerTest {

    private fun testKeyHeader(): KeyHeader = KeyHeader(
        iv = ByteArray(16),
        aesKey = SecureByteArray(ByteArray(16)),
    )

    private fun buildStickerFile(
        fileId: Uuid = Uuid.random(),
        driveId: Uuid = Uuid.random(),
        uniqueId: Uuid? = Uuid.random(),
        groupId: Uuid? = null,
        payloads: List<PayloadDescriptor>? = listOf(
            PayloadDescriptor(
                key = StickerProtocol.STICKER_PAYLOAD_KEY,
                contentType = "image/png",
                iv = Base64.encode(ByteArray(16)),
                descriptorContent = DescriptorContent.descriptorContentFromImage(isSticker = true),
                bytesWritten = 512L,
            )
        ),
        contentJson: String? = OdinSystemSerializer.serialize(StickerFileContent(name = "smile")),
    ): HomebaseFile = HomebaseFile(
        fileId = fileId,
        driveId = driveId,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = testKeyHeader(),
        fileMetadata = FileMetadata(
            created = UnixTimeUtc(1_700_000_000_000L),
            isEncrypted = true,
            appData = AppFileMetaData(
                uniqueId = uniqueId,
                fileType = StickerProtocol.STICKER_FILE_TYPE,
                groupId = groupId,
                content = contentJson,
            ),
            payloads = payloads,
        ),
        serverMetadata = ServerMetadata(
            accessControlList = AccessControlList(requiredSecurityGroup = "owner"),
        ),
    )

    @Test
    fun stickerFileType_isReservedValue() {
        assertEquals(7060, StickerProtocol.STICKER_FILE_TYPE)
        assertEquals("sticker_0", StickerProtocol.STICKER_PAYLOAD_KEY)
    }

    @Test
    fun stickerPayloadKey_matchesServerPattern() {
        // The drive payload/thumb endpoints reject keys that don't match this pattern with
        // "Missing payload key" (a 400). "stk_00" (6 chars) failed; the key must be 8–10
        // chars of [a-z0-9_]. This guards against a regression that breaks sticker thumbnails.
        assertTrue(
            StickerProtocol.STICKER_PAYLOAD_KEY.matches(Regex("^[a-z0-9_]{8,10}$")),
            "STICKER_PAYLOAD_KEY '${StickerProtocol.STICKER_PAYLOAD_KEY}' must match ^[a-z0-9_]{8,10}\$",
        )
    }

    @Test
    fun toSavedSticker_mapsSinglePayload() {
        val fileId = Uuid.random()
        val driveId = Uuid.random()
        val uniqueId = Uuid.random()
        val sticker = buildStickerFile(
            fileId = fileId,
            driveId = driveId,
            uniqueId = uniqueId,
        ).toSavedSticker()

        assertNotNull(sticker)
        assertEquals(fileId, sticker.fileId)
        assertEquals(driveId, sticker.driveId)
        assertEquals(uniqueId, sticker.uniqueId)
        assertEquals(StickerProtocol.STICKER_PAYLOAD_KEY, sticker.payloadKey)
        assertEquals("image/png", sticker.contentType)
        assertNull(sticker.groupId)
    }

    @Test
    fun toSavedSticker_payloadDescriptor_carriesStickerFlag() {
        val sticker = buildStickerFile().toSavedSticker()
        assertNotNull(sticker)
        val info = sticker.payloadDescriptor.descriptorInfo()
        assertTrue(info is DescriptorContent.ImageFile)
        assertTrue(info.isSticker, "saved sticker payload must carry isSticker=true")
    }

    @Test
    fun toSavedSticker_nullWhenNoPayload() {
        val sticker = buildStickerFile(payloads = null).toSavedSticker()
        assertNull(sticker)
    }

    @Test
    fun toSavedSticker_reservesGroupIdForPacks() {
        val groupId = Uuid.random()
        val sticker = buildStickerFile(groupId = groupId).toSavedSticker()
        assertNotNull(sticker)
        assertEquals(groupId, sticker.groupId)
    }

    @Test
    fun toSavedSticker_missingUniqueId_fallsBackToFileId() {
        val fileId = Uuid.random()
        val sticker = buildStickerFile(fileId = fileId, uniqueId = null).toSavedSticker()
        assertNotNull(sticker)
        assertEquals(fileId, sticker.uniqueId)
    }

    @Test
    fun toSavedSticker_readsSourceFileIdFromContent() {
        val source = Uuid.random()
        val sticker = buildStickerFile(
            contentJson = OdinSystemSerializer.serialize(
                StickerFileContent(name = "smile", sourceFileId = source)
            ),
        ).toSavedSticker()
        assertNotNull(sticker)
        assertEquals(source, sticker.sourceFileId)
    }

    @Test
    fun toSavedSticker_sourceFileIdNullForLegacyContent() {
        // A legacy sticker file (content without sourceFileId) maps to a null source — it was
        // not saved from a known message, so the bottom sheet can't claim a duplicate.
        val sticker = buildStickerFile(
            contentJson = OdinSystemSerializer.serialize(StickerFileContent(name = "smile")),
        ).toSavedSticker()
        assertNotNull(sticker)
        assertNull(sticker.sourceFileId)
    }

    @Test
    fun toSavedSticker_sourceFileIdNullForBlankContent() {
        val sticker = buildStickerFile(contentJson = null).toSavedSticker()
        assertNotNull(sticker)
        assertNull(sticker.sourceFileId)
    }

    @Test
    fun toImageData_decodesIvIntoKeyHeader() {
        val sticker = buildStickerFile().toSavedSticker()
        assertNotNull(sticker)
        val data = sticker.toImageData()
        assertNotNull(data)
        assertEquals(sticker.fileId, data.fileId)
        assertEquals(sticker.driveId, data.driveId)
        assertEquals(StickerProtocol.STICKER_PAYLOAD_KEY, data.payloadKey)
        assertTrue(data.isEncrypted)
    }

    @Test
    fun toImageData_nullWhenNoIv() {
        val sticker = buildStickerFile(
            payloads = listOf(
                PayloadDescriptor(
                    key = StickerProtocol.STICKER_PAYLOAD_KEY,
                    contentType = "image/png",
                    iv = null,
                )
            ),
        ).toSavedSticker()
        assertNotNull(sticker)
        assertNull(sticker.toImageData())
    }
}
