package id.homebase.api.client.drives.upload

import id.homebase.api.client.drives.files.PayloadFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the per-payload IV handling in [UploadManifest.build] and
 * [UpdateManifest.build]. The server rejects encrypted uploads with `Iv is null`
 * when payload descriptors lack an IV, so these semantics are load-bearing.
 */
class UploadManifestTest {

    private fun payload(iv: ByteArray? = null) = PayloadFile(
        key = "pyld_0",
        filePath = "/tmp/fake",
        contentType = "application/octet-stream",
        iv = iv
    )

    // ---- UploadManifest.build (new-file path) ----

    @Test
    fun upload_generatePayloadIvTrue_nullPayloadIv_producesIv() {
        val manifest = UploadManifest.build(
            payloads = listOf(payload(iv = null)),
            generatePayloadIv = true
        )

        val descriptor = manifest.payloadDescriptors?.single()
        assertNotNull(descriptor)
        val iv = descriptor.iv
        assertNotNull(iv, "generatePayloadIv=true must synthesize an IV when payload.iv is null")
        assertEquals(16, iv.size, "synthesized IV must be 16 bytes for AES-CBC")
    }

    @Test
    fun upload_generatePayloadIvFalse_nullPayloadIv_leavesIvNull() {
        val manifest = UploadManifest.build(
            payloads = listOf(payload(iv = null)),
            generatePayloadIv = false
        )

        assertNull(manifest.payloadDescriptors?.single()?.iv)
    }

    @Test
    fun upload_explicitPayloadIv_winsOverFlag() {
        val explicit = ByteArray(16) { it.toByte() }

        val withFlag = UploadManifest.build(
            payloads = listOf(payload(iv = explicit)),
            generatePayloadIv = true
        )
        val withoutFlag = UploadManifest.build(
            payloads = listOf(payload(iv = explicit)),
            generatePayloadIv = false
        )

        assertTrue(withFlag.payloadDescriptors?.single()?.iv.contentEquals(explicit))
        assertTrue(withoutFlag.payloadDescriptors?.single()?.iv.contentEquals(explicit))
    }

    // ---- UpdateManifest.build (edit-file path — this is what actually regressed) ----

    @Test
    fun update_generatePayloadIvTrue_nullPayloadIv_producesIv() {
        val manifest = UpdateManifest.build(
            payloads = listOf(payload(iv = null)),
            generatePayloadIv = true
        )

        val descriptor = manifest.payloadDescriptors?.single()
        assertNotNull(descriptor)
        assertEquals(PayloadOperationType.AppendOrOverwrite, descriptor.operationType)
        val iv = descriptor.iv
        assertNotNull(iv, "generatePayloadIv=true must synthesize an IV for encrypted updates")
        assertEquals(16, iv.size)
    }

    @Test
    fun update_generatePayloadIvFalse_nullPayloadIv_leavesIvNull() {
        val manifest = UpdateManifest.build(
            payloads = listOf(payload(iv = null)),
            generatePayloadIv = false
        )

        // Documents the pre-fix behavior — the server would have rejected this with 'Iv is null'.
        assertNull(manifest.payloadDescriptors?.single()?.iv)
    }

    @Test
    fun update_explicitPayloadIv_winsOverFlag() {
        val explicit = ByteArray(16) { (it + 1).toByte() }

        val withFlag = UpdateManifest.build(
            payloads = listOf(payload(iv = explicit)),
            generatePayloadIv = true
        )
        val withoutFlag = UpdateManifest.build(
            payloads = listOf(payload(iv = explicit)),
            generatePayloadIv = false
        )

        assertTrue(withFlag.payloadDescriptors?.single()?.iv.contentEquals(explicit))
        assertTrue(withoutFlag.payloadDescriptors?.single()?.iv.contentEquals(explicit))
    }

    @Test
    fun update_everyDescriptorGetsIndependentIv() {
        val manifest = UpdateManifest.build(
            payloads = listOf(
                payload(iv = null).copy(key = "pyld_a"),
                payload(iv = null).copy(key = "pyld_b"),
            ),
            generatePayloadIv = true
        )

        val descriptors = manifest.payloadDescriptors
        assertNotNull(descriptors)
        assertEquals(2, descriptors.size)
        val ivA = descriptors[0].iv
        val ivB = descriptors[1].iv
        assertNotNull(ivA)
        assertNotNull(ivB)
        assertTrue(!ivA.contentEquals(ivB), "each payload should receive its own random IV")
    }
}
