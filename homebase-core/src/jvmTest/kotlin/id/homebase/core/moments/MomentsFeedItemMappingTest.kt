@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.moments

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.core.moments.services.toFeedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class MomentsFeedItemMappingTest {

    private fun file(payloads: List<PayloadDescriptor>) = HomebaseFile(
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        serverFileIsEncrypted = true,
        fileMetadata = FileMetadata(
            created = UnixTimeUtc(1_700_000_000_000L),
            isEncrypted = true,
            appData = AppFileMetaData(uniqueId = Uuid.random()),
            payloads = payloads,
        ),
        serverMetadata = ServerMetadata(
            accessControlList = AccessControlList(requiredSecurityGroup = "owner"),
        ),
    )

    @Test
    fun toFeedItem_dropsInternalVideoDescriptorPayload() {
        val video = PayloadDescriptor(key = "mmnt_pl0", contentType = "video/mp4", bytesWritten = 9_000L)
        val descriptor = PayloadDescriptor(key = "pld_desc0", contentType = "application/json", bytesWritten = 1_400L)

        val item = file(listOf(video, descriptor)).toFeedItem()

        assertNotNull(item)
        assertEquals(listOf(video), item.payloads)
    }
}
