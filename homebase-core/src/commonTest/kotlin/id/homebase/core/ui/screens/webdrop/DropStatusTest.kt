@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.webdrop

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.core.ui.screens.webdrop.model.DropStatus
import id.homebase.core.ui.screens.webdrop.model.dropStatusOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Status is derived, never bookkept: the drop file the owner's device syncs back carries the
 * resolved ttl, and expiry soft-deletes so a tombstone is observable. Whether a positive ttl
 * means "opened" depends on what the drop started as — only a burn drop's ttl flips sign.
 */
class DropStatusTest {

    private fun dropFile(ttl: Long?, state: FileState = FileState.Active) = HomebaseFile(
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        fileState = state,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader.empty(),
        fileMetadata = FileMetadata(appData = AppFileMetaData(), ttl = ttl),
        serverMetadata = ServerMetadata(),
    )

    @Test
    fun aBurnDropNobodyOpenedIsWaiting() {
        assertEquals(DropStatus.Waiting, dropStatusOf(dropFile(ttl = -1_200_000), receiptTtl = -1_200_000))
    }

    @Test
    fun aBurnDropWithAResolvedTtlWasOpened() {
        val status = dropStatusOf(dropFile(ttl = 1_800_000_000_000), receiptTtl = -1_200_000)
        assertEquals(DropStatus.Opened(diesAtMs = 1_800_000_000_000), status)
    }

    @Test
    fun aFixedLifetimeDropIsExpiringNotOpened() {
        val status = dropStatusOf(dropFile(ttl = 1_800_000_000_000), receiptTtl = 1_800_000_000_000)
        assertEquals(DropStatus.Expiring(diesAtMs = 1_800_000_000_000), status)
    }

    @Test
    fun aTombstonedDropIsRemoved() {
        assertEquals(
            DropStatus.Removed,
            dropStatusOf(dropFile(ttl = 1_800_000_000_000, state = FileState.Deleted), receiptTtl = -1_200_000),
        )
    }

    @Test
    fun aMissingDropFileIsRemoved() {
        assertEquals(DropStatus.Removed, dropStatusOf(null, receiptTtl = -1_200_000))
    }
}
