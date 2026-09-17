package id.homebase.core.ui.screens.vault

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.ui.graphics.vector.ImageVector
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.ui.screens.vault.components.fileTypeIcon
import id.homebase.core.ui.screens.vault.components.pageTypeIcon
import id.homebase.core.ui.screens.vault.model.VaultEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class VaultFileIconTest {

    @Test
    fun eachFileKindMapsToItsIcon() {
        listOf(
            "image/png" to Icons.Outlined.Image,
            "video/mp4" to Icons.Outlined.VideoFile,
            "audio/mpeg" to Icons.Outlined.AudioFile,
            "application/pdf" to Icons.Outlined.PictureAsPdf,
            "application/json" to Icons.Outlined.Code,
            "text/csv" to Icons.Outlined.TableChart,
            "application/vnd.ms-powerpoint" to Icons.Outlined.Slideshow,
            "application/msword" to Icons.AutoMirrored.Outlined.Article,
            "application/zip" to Icons.Outlined.FolderZip,
            "text/plain" to Icons.Outlined.Description,
            "application/vnd.android.package-archive" to Icons.AutoMirrored.Outlined.InsertDriveFile,
            "application/octet-stream" to Icons.AutoMirrored.Outlined.InsertDriveFile,
        ).forEach { (contentType, expected) -> assertIcon(expected, contentType, fileName = null) }
    }

    @Test
    fun markdownIsANote() {
        assertIcon(Icons.AutoMirrored.Outlined.NoteAdd, "text/markdown", fileName = null)
    }

    @Test
    fun unresolvedMimeFallsBackToExtension() {
        assertIcon(Icons.Outlined.TableChart, "application/octet-stream", "budget.ods")
        assertIcon(Icons.Outlined.FolderZip, "application/octet-stream", "archive.zip")
        assertIcon(Icons.Outlined.PictureAsPdf, null, "report.pdf")
        assertIcon(Icons.Outlined.Description, "application/octet-stream", "notes.md")
    }

    @Test
    fun pageTypeIcon_onlyFirstPageUsesEntryName() {
        val entry = VaultEntry(
            fileId = Uuid.random(),
            uniqueId = Uuid.random(),
            driveId = Uuid.random(),
            fileName = "archive.zip",
            contentType = "application/octet-stream",
            sizeBytes = 0L,
            createdAt = 0L,
            previewThumbnail = null,
            keyHeader = KeyHeader.empty(),
            isEncrypted = true,
            versionTag = null,
            payloadDescriptors = listOf(
                PayloadDescriptor(key = "vlt_pg_00", contentType = "application/octet-stream"),
                PayloadDescriptor(key = "vlt_pg_01", contentType = "application/octet-stream"),
            ),
        )
        assertEquals(Icons.Outlined.FolderZip.name, entry.pageTypeIcon(entry.payloadDescriptors[0]).name)
        assertEquals(
            Icons.AutoMirrored.Outlined.InsertDriveFile.name,
            entry.pageTypeIcon(entry.payloadDescriptors[1]).name,
        )
    }

    private fun assertIcon(expected: ImageVector, contentType: String?, fileName: String?) {
        assertEquals(
            expected.name,
            fileTypeIcon(contentType, fileName).name,
            "icon for contentType=$contentType fileName=$fileName",
        )
    }
}
