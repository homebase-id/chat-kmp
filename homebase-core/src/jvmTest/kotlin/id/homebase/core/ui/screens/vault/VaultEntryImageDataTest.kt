@file:OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)

package id.homebase.core.ui.screens.vault

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.SecureByteArray
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.ui.screens.vault.model.imageDataFor
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Unit tests for [imageDataFor] — the single source of truth that the Vault
 * grid thumbnail, the press-time prefetch, and the fullscreen viewer all use to
 * build a [id.homebase.core.image.HomebaseImageData]. Pins the
 * null-on-missing-IV contract and the identity-field + KeyHeader mapping so the
 * three call sites cannot drift apart and miss each other's Coil / byte-cache
 * entries (whose keys derive from drive/file/payload/lastModified).
 */
class VaultEntryImageDataTest {

    private val aesKey = SecureByteArray(ByteArray(16) { 7 })

    private fun vaultEntry(
        driveId: Uuid = Uuid.random(),
        fileId: Uuid = Uuid.random(),
        isEncrypted: Boolean = true,
    ): VaultEntry = VaultEntry(
        fileId = fileId,
        uniqueId = fileId,
        driveId = driveId,
        fileName = "photo.jpg",
        contentType = "image/jpeg",
        sizeBytes = 1024L,
        createdAt = 1_700_000_000_000L,
        previewThumbnail = null,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = aesKey),
        isEncrypted = isEncrypted,
        versionTag = null,
    )

    @Test
    fun `maps identity fields and decodes the iv into the key header`() {
        val ivBytes = ByteArray(16) { it.toByte() }
        val entry = vaultEntry()
        val descriptor = PayloadDescriptor(
            key = "vlt_pg_00",
            contentType = "image/jpeg",
            iv = Base64.encode(ivBytes),
            lastModified = 42L,
        )

        val data = assertNotNull(entry.imageDataFor(descriptor, loadFullPayload = true))

        assertEquals(entry.driveId, data.driveId)
        assertEquals(entry.fileId, data.fileId)
        assertEquals("vlt_pg_00", data.payloadKey)
        assertEquals(42L, data.lastModified)
        assertTrue(data.isEncrypted)
        assertTrue(data.loadFullPayload)
        assertTrue(ivBytes contentEquals data.keyHeader.iv)
        // The entry's AES key is reused as-is (passed through, not rebuilt).
        assertSame(entry.keyHeader.aesKey, data.keyHeader.aesKey)
    }

    @Test
    fun `passes through preview thumbnail and requested size for the thumbnail variant`() {
        val entry = vaultEntry()
        val thumb = EmbeddedThumb(
            pixelWidth = 10,
            pixelHeight = 10,
            contentType = "image/jpeg",
            content = "abc",
        )
        val descriptor = PayloadDescriptor(
            key = "k",
            contentType = "image/jpeg",
            iv = Base64.encode(ByteArray(16)),
        )

        val data = assertNotNull(
            entry.imageDataFor(
                descriptor,
                loadFullPayload = false,
                previewThumbnail = thumb,
                requestedSize = ImageSize.THUMB_MEDIUM,
            )
        )

        assertFalse(data.loadFullPayload)
        assertEquals(thumb, data.previewThumbnail)
        assertEquals(ImageSize.THUMB_MEDIUM, data.requestedSize)
    }

    @Test
    fun `returns null when the descriptor has no iv`() {
        val entry = vaultEntry()
        val descriptor = PayloadDescriptor(key = "k", contentType = "image/jpeg", iv = null)

        assertNull(entry.imageDataFor(descriptor, loadFullPayload = true))
    }

    @Test
    fun `returns null when the iv is not valid base64`() {
        val entry = vaultEntry()
        val descriptor = PayloadDescriptor(key = "k", contentType = "image/jpeg", iv = "%%%%")

        assertNull(entry.imageDataFor(descriptor, loadFullPayload = true))
    }

    @Test
    fun `threads the descriptor content type as the payload content type`() {
        // A GIF's preview thumbnail is a tiny WebP, so the only way the loader
        // can tell it is a thumbless format is the real payload content type
        // carried here. Without it the grid thumbnail request would chase a
        // server thumbnail that was never generated for a GIF (NotFound).
        val entry = vaultEntry()
        val descriptor = PayloadDescriptor(
            key = "vlt_pg_00",
            contentType = "image/gif",
            iv = Base64.encode(ByteArray(16)),
        )

        val data = assertNotNull(entry.imageDataFor(descriptor, loadFullPayload = false))

        assertEquals("image/gif", data.payloadContentType)
        // effectiveContentType prefers the payload type over the (absent) preview.
        assertEquals("image/gif", data.effectiveContentType)
    }
}
