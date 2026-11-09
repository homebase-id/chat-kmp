package id.homebase.api.client.drives.files

import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ExistingFileIsSuccessTest {
    private fun request(existingFileIsSuccess: Boolean) = UploadFileRequest(
        driveId = Uuid.random(),
        keyHeader = KeyHeader.newRandom16(),
        metadata = UploadFileMetadata(allowDistribution = false, isEncrypted = true, appData = UploadAppFileMetaData(uniqueId = Uuid.random())),
        existingFileIsSuccess = existingFileIsSuccess,
    )

    private fun failure(code: OdinClientErrorCode) =
        ClientException(status = 400, errorCode = code, message = "m", correlationId = null, problem = ProblemDetails(status = 400, title = "m"))

    @Test
    fun onlyAFlaggedRequestCountsA4105AsSent() {
        val existing = failure(OdinClientErrorCode.ExistingFileWithUniqueId)
        assertTrue(request(existingFileIsSuccess = true).countsAsSent(existing))
        assertFalse(request(existingFileIsSuccess = false).countsAsSent(existing))
    }

    @Test
    fun otherRefusalsStayFailures() {
        assertFalse(request(existingFileIsSuccess = true).countsAsSent(failure(OdinClientErrorCode.UnhandledScenario)))
    }

    @Test
    fun theFlagSurvivesTheOutboxRow() {
        val json = OdinSystemSerializer.serialize(request(existingFileIsSuccess = true))
        assertTrue(OdinSystemSerializer.deserialize<UploadFileRequest>(json).existingFileIsSuccess)
    }

    @Test
    fun rowsQueuedBeforeTheFlagExistedKeepTheOldBehaviour() {
        val json = OdinSystemSerializer.serialize(request(existingFileIsSuccess = false))
        val withoutField = json.replace("\"existingFileIsSuccess\":false,", "").replace(",\"existingFileIsSuccess\":false", "")
        assertEquals(false, OdinSystemSerializer.deserialize<UploadFileRequest>(withoutField).existingFileIsSuccess)
    }
}
