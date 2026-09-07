@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.drives.upload

import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * The ttl field crosses two wire boundaries — the upload descriptor going to the server and the
 * file header coming back — and a pre-ttl server simply omits it. Both directions must agree on
 * the name (`ttl`, camelCase) and both must read "absent" as "never expires".
 */
class FileTtlSerializationTest {

    @Test
    fun uploadMetadataCarriesTtlOnTheWire() {
        val metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = false,
            appData = UploadAppFileMetaData(),
            ttl = -1_200_000,
        )

        val json = OdinSystemSerializer.serialize(metadata)
        assertTrue(json.contains("\"ttl\":-1200000"), json)

        val back = OdinSystemSerializer.deserialize<UploadFileMetadata>(json)
        assertEquals(-1_200_000, back.ttl)
    }

    @Test
    fun uploadMetadataWithoutTtlStaysNull() {
        val json = """{"allowDistribution":false,"isEncrypted":false,"appData":{}}"""
        val back = OdinSystemSerializer.deserialize<UploadFileMetadata>(json)
        assertNull(back.ttl)
    }

    @Test
    fun fileMetadataFromAPreTtlServerReadsAsNeverExpires() {
        val json = """{"isEncrypted":false,"appData":{}}"""
        val back = OdinSystemSerializer.deserialize<FileMetadata>(json)
        assertNull(back.ttl)
    }

    @Test
    fun fileMetadataReadsAResolvedTtlBack() {
        val json = """{"isEncrypted":false,"appData":{},"ttl":1795000000000}"""
        val back = OdinSystemSerializer.deserialize<FileMetadata>(json)
        assertEquals(1_795_000_000_000, back.ttl)
    }
}
