package id.homebase.api.serialization

import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.client.drives.files.FileVersionTagBatch
import id.homebase.api.client.drives.files.PerFileBatchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** odin-core's hard-, soft-delete- and update-archival-status-batch: `{files: [{fileId, versionTag}]}` in, one `{fileId, errorCode}` per file out. */
class DeleteBatchSerializationTest {

    private val file = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val tag = Uuid.parse("66666666-7777-8888-9999-aaaaaaaaaaaa")

    @Test
    fun theRequestNamesEachFileWithItsVersion() {
        assertEquals(
            """{"files":[{"fileId":"$file","versionTag":"$tag"}]}""",
            OdinSystemSerializer.serialize(FileVersionTagBatch(listOf(FileVersionTag(file, tag)))),
        )
    }

    @Test
    fun anArchivalStatusBatchNamesTheStatus() {
        assertEquals(
            """{"files":[{"fileId":"$file","versionTag":"$tag"}],"archivalStatus":0}""",
            OdinSystemSerializer.serialize(FileVersionTagBatch(listOf(FileVersionTag(file, tag)), ArchivalStatus.None)),
        )
    }

    @Test
    fun eachFileAnswersGoneOrWhyItStayed() {
        val result = OdinSystemSerializer.json.decodeFromString(
            PerFileBatchResult.serializer(),
            """{"results":[{"fileId":"$file","errorCode":null},{"fileId":"$file","errorCode":"versionTagMismatch"},{"fileId":"$file","errorCode":"fileNotFound"}]}""",
        )
        assertEquals(
            listOf(null, OdinClientErrorCode.VersionTagMismatch, OdinClientErrorCode.FileNotFound),
            result.results.map { it.errorCode },
        )
    }

    @Test
    fun aSoftDeleteAnswersTheSameWithItsTombstoneAndRecipients() {
        val result = OdinSystemSerializer.json.decodeFromString(
            PerFileBatchResult.serializer(),
            """{"results":[{"fileId":"$file","errorCode":null,"updated":1760780000000,"recipientStatus":{"sam.dotyou.cloud":"enqueued"}},""" +
                """{"fileId":"$file","errorCode":"versionTagMismatch","updated":null,"recipientStatus":{}}]}""",
        )
        assertEquals(listOf(null, OdinClientErrorCode.VersionTagMismatch), result.results.map { it.errorCode })
    }
}
