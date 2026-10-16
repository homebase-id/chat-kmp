package id.homebase.api.serialization

import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.client.drives.files.HardDeleteBatchRequest
import id.homebase.api.client.drives.files.HardDeleteBatchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** odin-core's hard-delete-batch: `{files: [{fileId, versionTag}]}` in, one `{fileId, errorCode}` per file out. */
class HardDeleteBatchSerializationTest {

    private val file = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val tag = Uuid.parse("66666666-7777-8888-9999-aaaaaaaaaaaa")

    @Test
    fun theRequestNamesEachFileWithItsVersion() {
        assertEquals(
            """{"files":[{"fileId":"$file","versionTag":"$tag"}]}""",
            OdinSystemSerializer.serialize(HardDeleteBatchRequest(listOf(FileVersionTag(file, tag)))),
        )
    }

    @Test
    fun eachFileAnswersGoneOrWhyItStayed() {
        val result = OdinSystemSerializer.json.decodeFromString(
            HardDeleteBatchResult.serializer(),
            """{"results":[{"fileId":"$file","errorCode":null},{"fileId":"$file","errorCode":"versionTagMismatch"},{"fileId":"$file","errorCode":"fileNotFound"}]}""",
        )
        assertEquals(
            listOf(null, OdinClientErrorCode.VersionTagMismatch, OdinClientErrorCode.FileNotFound),
            result.results.map { it.errorCode },
        )
    }
}
